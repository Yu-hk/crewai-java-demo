package io.example.crew.core;

import io.example.crew.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 基于本地文件的 {@link RunStore}。
 *
 * <p>零依赖是硬约束（JDK 不自带 SQLite 驱动），因此用「一个 runId 一个 JSON 文件」实现。
 * 对单机单流程足够；并发多写同一 runId 不支持——那需要数据库的行锁，文件系统给不了。
 *
 * <p>两个必须做对的细节：
 * <ol>
 *   <li><b>原子写</b>：先写 {@code .tmp} 再 {@code ATOMIC_MOVE}。直接覆写时若进程被杀，
 *       会留下半截 JSON，下次启动读不出来——这比没有存档更糟，因为它看起来"有"。</li>
 *   <li><b>runId 白名单</b>：只允许字母数字与下划线横线。否则 {@code ../} 就能读出
 *       任意文件，或者把档案写到目录之外。</li>
 * </ol>
 */
public final class FileRunStore implements RunStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String SUFFIX = ".json";

    private final Path directory;

    public FileRunStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建存档目录: " + directory, e);
        }
    }

    /** 便捷构造：在当前工作目录下建 {@code runs/}。 */
    public static FileRunStore ofDefaults() {
        return new FileRunStore(Path.of("runs"));
    }

    public Path directory() {
        return directory;
    }

    @Override
    public Optional<RunState> load(String runId) {
        Path file = fileOf(runId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(RunState.fromJson(Json.parseObject(text)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取存档失败: " + file, e);
        } catch (RuntimeException e) {
            // 存档损坏（例如旧版本格式）不应让整条流程起不来，按"无存档"处理并提示
            io.example.crew.util.Console.warn("存档不可读，已忽略: " + file.getFileName()
                    + "（" + e.getMessage() + "）");
            return Optional.empty();
        }
    }

    @Override
    public void save(RunState state) {
        Path target = fileOf(state.runId());
        Path tmp = directory.resolve(state.runId() + SUFFIX + ".tmp");
        try {
            Files.writeString(tmp, Json.write(state.toJson()), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                // 部分文件系统（网络盘、FAT32）不支持原子移动，退化为普通覆盖
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入存档失败: " + target, e);
        }
    }

    @Override
    public List<String> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .toList());
            files.sort(Comparator.comparingLong(FileRunStore::lastModified).reversed());
            return files.stream()
                    .map(p -> p.getFileName().toString()
                            .substring(0, p.getFileName().toString().length() - SUFFIX.length()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列目录失败: " + directory, e);
        }
    }

    @Override
    public void delete(String runId) {
        try {
            Files.deleteIfExists(fileOf(runId));
        } catch (IOException e) {
            throw new UncheckedIOException("删除存档失败: " + runId, e);
        }
    }

    private Path fileOf(String runId) {
        if (runId == null || !SAFE_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException(
                    "runId 只允许字母、数字、下划线与横线（1-64 位），收到: " + runId);
        }
        return directory.resolve(runId + SUFFIX);
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
