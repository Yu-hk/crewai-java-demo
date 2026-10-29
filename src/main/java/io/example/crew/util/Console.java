package io.example.crew.util;

/**
 * 控制台渲染工具：负责配色、分节、缩进，保持输出可读。
 */
public final class Console {

    private static final boolean COLOR = System.getenv("NO_COLOR") == null;

    private Console() {
    }

    private static String wrap(String code, String s) {
        return COLOR ? "\u001B[" + code + "m" + s + "\u001B[0m" : s;
    }

    public static String bold(String s) {
        return wrap("1", s);
    }

    public static String dim(String s) {
        return wrap("2", s);
    }

    public static String cyan(String s) {
        return wrap("36", s);
    }

    public static String green(String s) {
        return wrap("32", s);
    }

    public static String yellow(String s) {
        return wrap("33", s);
    }

    public static String gray(String s) {
        return wrap("90", s);
    }

    public static void title(String s) {
        System.out.println();
        System.out.println(bold("━".repeat(72)));
        System.out.println(bold(" " + s));
        System.out.println(bold("━".repeat(72)));
    }

    public static void section(String s) {
        System.out.println();
        System.out.println(cyan("▌ " + s));
    }

    /** 打印缩进键值对，如 "├ 角色: 研究员"。 */
    public static void field(String key, String value) {
        System.out.println(gray("  │ ") + dim(key + ": ") + value);
    }

    public static void bullet(String s) {
        System.out.println(gray("  ├ ") + s);
    }

    public static void last(String s) {
        System.out.println(gray("  └ ") + s);
    }

    public static void info(String s) {
        System.out.println(dim(s));
    }

    public static void warn(String s) {
        System.out.println(yellow("! " + s));
    }

    public static void error(String s) {
        System.out.println(yellow("× " + s));
    }

    /** 缩进整段文本，保留原有换行结构。 */
    public static void block(String text, String indent) {
        if (text == null || text.isBlank()) {
            System.out.println(indent + dim("(空)"));
            return;
        }
        for (String line : text.strip().split("\n")) {
            System.out.println(indent + line);
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    public static String chars(int n) {
        return n + " 字";
    }

    public static String millis(long ms) {
        return String.format("%.1fs", ms / 1000.0);
    }
}
