package io.example.crew.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 基于子进程标准输入输出的 MCP 传输。
 *
 * <p>协议规定一条消息就是一行 JSON，以 {@code \n} 分隔，消息体内不允许出现裸换行
 * （实现方必须转义）。因此这里不做长度前缀、不做 Content-Length 头——那是 LSP 的做法。
 *
 * <p>三个容易踩的细节，都在代码里显式处理了：
 * <ul>
 *   <li><b>编码必须显式 UTF-8</b>。Windows 上进程管道默认走系统编码（GBK），
 *       工具返回的中文会变成乱码，而且不会报错，只是"模型看不懂工具结果"。</li>
 *   <li><b>stderr 必须有人读</b>。管道缓冲区写满后子进程会阻塞在 stderr 写入上，
 *       表现为整个客户端莫名卡死。这里用后台线程持续排空并只保留尾部若干行。</li>
 *   <li><b>Windows 上 .cmd 要经过 shell</b>。{@code npx} 实际是 {@code npx.cmd}，
 *       CreateProcess 不能直接执行，需要 {@code cmd /c} 包一层。</li>
 * </ul>
 */
public final class StdioTransport implements McpTransport {

    private static final int STDERR_KEEP_LINES = 40;

    private final List<String> command;
    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final Thread stderrPump;

    public StdioTransport(List<String> command) {
        this(command, java.util.Map.of());
    }

    public StdioTransport(List<String> command, java.util.Map<String, String> env) {
        if (command == null || command.isEmpty()) {
            throw new McpException("启动命令为空");
        }
        this.command = List.copyOf(command);
        this.process = start(command, env);
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.stderrPump = pumpStderr(process);
    }

    private static Process start(List<String> command, java.util.Map<String, String> env) {
        try {
            return buildProcess(command, env).start();
        } catch (IOException first) {
            // Windows 上 npx / uvx 之类是 .cmd 脚本，Java 的 CreateProcess 走不通时再套一层 shell。
            if (isWindows() && !looksLikeExecutable(command.get(0))) {
                List<String> wrapped = new ArrayList<>();
                wrapped.add("cmd.exe");
                wrapped.add("/c");
                wrapped.addAll(command);
                try {
                    return buildProcess(wrapped, env).start();
                } catch (IOException second) {
                    throw new McpException("无法启动 MCP 服务端：" + String.join(" ", command)
                            + "（首次失败：" + first.getMessage() + "；经 cmd /c 也失败：" + second.getMessage() + "）", second);
                }
            }
            throw new McpException("无法启动 MCP 服务端：" + String.join(" ", command)
                    + " —— " + first.getMessage() + "（请确认命令在 PATH 中）", first);
        }
    }

    private static ProcessBuilder buildProcess(List<String> command, java.util.Map<String, String> env) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        return builder;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean looksLikeExecutable(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".com");
    }

    /**
     * 排空子进程 stderr。
     *
     * <p>不能简单用 {@code BufferedReader + UTF-8}：stderr 里混着两种来源——
     * 服务端自己的日志（Node 是 UTF-8）和 shell 的错误提示（Windows cmd 是 GBK）。
     * 统一按 UTF-8 解会把后者变成一堆问号，而那正是排障时最需要读的一行。
     * 这里按字节收行，再根据是否出现替换字符决定用哪种编码解码。
     */
    private Thread pumpStderr(Process process) {
        Thread thread = new Thread(() -> {
            try (java.io.BufferedInputStream in = new java.io.BufferedInputStream(process.getErrorStream())) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(256);
                int b;
                while ((b = in.read()) != -1) {
                    if (b == '\n') {
                        record(decode(buffer.toByteArray()));
                        buffer.reset();
                    } else if (b != '\r') {
                        buffer.write(b);
                        // 防御单行超长：日志行不该有这么大，超过就截断丢弃尾部
                        if (buffer.size() > 8192) {
                            record(decode(buffer.toByteArray()) + " …(截断)");
                            buffer.reset();
                        }
                    }
                }
                if (buffer.size() > 0) {
                    record(decode(buffer.toByteArray()));
                }
            } catch (IOException ignored) {
                // 进程结束导致的读失败无需处理，已收集的行仍然有效
            }
        }, "mcp-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void record(String line) {
        synchronized (stderrTail) {
            stderrTail.addLast(line);
            while (stderrTail.size() > STDERR_KEEP_LINES) {
                stderrTail.removeFirst();
            }
        }
    }

    private static String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            return new String(bytes, java.nio.charset.Charset.defaultCharset());
        }
        return utf8;
    }

    @Override
    public void send(String message) throws IOException {
        if (!alive()) {
            throw new McpException("MCP 服务端已退出，无法发送：" + diagnostics());
        }
        stdin.write(message);
        stdin.write('\n');
        stdin.flush();
    }

    @Override
    public String receive() throws IOException {
        return stdout.readLine();
    }

    @Override
    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public String diagnostics() {
        String joined;
        synchronized (stderrTail) {
            joined = String.join("\n", stderrTail);
        }
        String exit = process.isAlive() ? "" : "（进程已退出，exit=" + process.exitValue() + "）";
        return exit + (joined.isBlank() ? "无 stderr 输出" : joined);
    }

    /** 启动命令，用于日志与错误定位。 */
    public List<String> command() {
        return command;
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 关不掉也无所谓，下面还有兜底
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        stderrPump.interrupt();
    }
}
