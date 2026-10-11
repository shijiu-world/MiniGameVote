package cn.shijiu.minigamevote;

import java.util.ArrayList;
import java.util.List;

/**
 * 直接改 config.toml 的**文本**（{@code /votegameadmin threshold} 用）。
 *
 * <p>为什么不重新序列化整个 TOML：那样会把服主写的注释、空行、排版全冲掉，
 * 服主回头一看「我的配置文件怎么变了个人样」。所以这里只动那一行，
 * 而且尽量保留原来的缩进和行尾注释。
 *
 * <h3>⚠ 之前那版踩的坑（这些就是「改了命令但配置文件看着没变」的原因）</h3>
 * <ol>
 *   <li><b>被注释掉的行会被跳过</b> —— 只认 {@code threshold = 5}，遇到
 *       {@code # threshold = 5} 直接 continue，最后把新行插到<b>文件最开头</b>，
 *       服主在原位置看到的还是那行注释，以为没生效。</li>
 *   <li><b>整行替换会吞掉行尾注释</b> —— {@code threshold = 5  # 达到多少票}
 *       被改成光秃秃的 {@code threshold = 3}。</li>
 *   <li><b>不认分节</b> —— 万一哪个分节里也有个 threshold，会改错行。</li>
 *   <li><b>没有任何回执</b> —— 命令只回显内存里的值，服主没法确认文件到底改没改。</li>
 * </ol>
 *
 * <p>现在：优先改顶层的有效行 → 其次是顶层被注释的那行（就地取消注释）
 * → 都没有就在第一个分节之前新增一行。改完把「第几行、原来写啥、现在写啥」
 * 回传给命令，让服主一眼能核对。
 */
public final class ConfigEdit {

    /**
     * 改一行的结果。
     *
     * <p>{@link #ok} 为 false 时只有 {@link #error} 有意义。
     */
    public static final class Result {
        public final boolean ok;

        /** 改的是第几行（从 1 开始）；新增行时是插入后它所在的行号 */
        public final int line;

        /** 改之前的原文（trim 过，用于回显）；新增时为 "" */
        public final String before;

        /** 改之后的整行原文（trim 过，用于回显） */
        public final String after;

        /** 原来那行是注释状态（# threshold = ...），这次顺手取消注释了 */
        public final boolean uncommented;

        /** 文件里原来没有这一项，是新增的一行 */
        public final boolean added;

        /** 失败原因（ok=false 时有值） */
        public final String error;

        private Result(boolean ok, int line, String before, String after,
                       boolean uncommented, boolean added, String error) {
            this.ok = ok;
            this.line = line;
            this.before = before;
            this.after = after;
            this.uncommented = uncommented;
            this.added = added;
            this.error = error;
        }

        static Result fail(String error) {
            return new Result(false, -1, "", "", false, false, error);
        }
    }

    /** 在文件里找到的那一行 */
    public static final class Found {
        /** 行号（从 1 开始）；没找到是 -1 */
        public final int line;
        /** 该行原文（trim 过） */
        public final String raw;
        /** 是不是被注释掉的 */
        public final boolean commented;

        Found(int line, String raw, boolean commented) {
            this.line = line;
            this.raw = raw;
            this.commented = commented;
        }

        /** 没找到（文件里既没有有效行，也没有被注释掉的同名行） */
        public boolean missing() {
            return line < 0;
        }
    }

    private ConfigEdit() {
    }

    /**
     * 在顶层（第一个 {@code [分节]} 之前）找 {@code key = ...}。
     *
     * <p>有效行优先；只有注释行时返回注释行 —— 这样改的时候可以就地取消注释，
     * 而不是跑到文件开头另起一行。
     */
    public static Found findTopLevel(List<String> lines, String key) {
        int end = firstSectionLine(lines);
        Found commented = null;
        for (int i = 0; i < end; i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }
            boolean isComment = trimmed.charAt(0) == '#';
            String name = keyOf(isComment ? stripHashes(trimmed) : trimmed);
            if (name == null || !name.equals(key)) {
                continue;
            }
            if (isComment) {
                if (commented == null) {
                    commented = new Found(i + 1, trimmed, true);
                }
            } else {
                return new Found(i + 1, trimmed, false);
            }
        }
        return commented != null ? commented : new Found(-1, "", false);
    }

    /**
     * 把顶层 {@code key} 的值改成 {@code value}，直接改传入的 List。
     *
     * <p>保留原来的缩进；如果是有效行，还保留行尾注释（{@code # ...}）。
     */
    public static Result setTopLevel(List<String> lines, String key, String value) {
        Found found = findTopLevel(lines, key);

        if (found.missing()) {
            // 顶层没有：插在第一个分节之前（也就是顶层最后），别插到文件开头那堆注释里去
            int at = firstSectionLine(lines);
            String line = key + " = " + value;
            lines.add(at, line);
            return new Result(true, at + 1, "", line, false, true, null);
        }

        int idx = found.line - 1;
        String raw = lines.get(idx);
        int hashCount = 0;
        int p = 0;
        while (p < raw.length() && (raw.charAt(p) == ' ' || raw.charAt(p) == '\t')) {
            p++;
        }
        int indentEnd = p;                      // 缩进结束的位置
        while (p < raw.length() && raw.charAt(p) == '#') {
            hashCount++;
            p++;
        }
        while (p < raw.length() && (raw.charAt(p) == ' ' || raw.charAt(p) == '\t')) {
            p++;
        }
        String indent = raw.substring(0, indentEnd);
        String body = raw.substring(p);         // "threshold = 5   # 说明"
        int eq = body.indexOf('=');
        if (eq < 0) {
            return Result.fail("第 " + found.line + " 行没有等号，无法改写");
        }
        String head = body.substring(0, eq).trim();         // "threshold"
        String tail = body.substring(eq + 1);               // " 5   # 说明"

        // 行尾注释：按引号状态扫，值里的 # 不算（threshold 是数字，但别把路走窄了）
        int hash = indexOfUnquoted(tail, '#');
        String comment = hash < 0 ? "" : tail.substring(hash).trim();
        // 原值是带引号的字符串 → 新值也要带引号，否则写进去就变成裸字符串了
        String old = (hash < 0 ? tail : tail.substring(0, hash)).trim();
        String newline = indent + head + " = " + quoteLike(old, value)
                + (comment.isEmpty() ? "" : "   " + comment);

        lines.set(idx, newline);
        return new Result(true, found.line, found.raw, newline.trim(), hashCount > 0, false, null);
    }

    /** 原值带引号就把新值也用同样的引号包起来；已经带了的就不重复加 */
    private static String quoteLike(String oldValue, String value) {
        if (oldValue.isEmpty()) {
            return value;
        }
        char q = oldValue.charAt(0);
        if (q != '"' && q != '\'') {
            return value;
        }
        if (value.length() >= 2 && value.charAt(0) == q && value.charAt(value.length() - 1) == q) {
            return value;
        }
        return q + value + q;
    }

    /** 第一个 {@code [分节]} 的行下标；没有分节返回 lines.size() */
    private static int firstSectionLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.isEmpty() && trimmed.charAt(0) == '[') {
                return i;
            }
        }
        return lines.size();
    }

    /** 取 {@code key = value} 里的 key；写歪了（key 里带空格、没有等号）返回 null */
    private static String keyOf(String body) {
        int eq = body.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = body.substring(0, eq).trim();
        if (key.isEmpty() || key.indexOf(' ') >= 0 || key.indexOf('\t') >= 0) {
            return null;
        }
        return key;
    }

    /** 去掉行首连续若干个 # 和紧随其后的空白 */
    private static String stripHashes(String s) {
        int p = 0;
        while (p < s.length() && s.charAt(p) == '#') {
            p++;
        }
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) {
            p++;
        }
        return s.substring(p);
    }

    /** 找不在引号里的目标字符（跟 Toml 那个一个思路） */
    private static int indexOfUnquoted(String s, char target) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote && s.charAt(i - 1) != '\\') {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == target) {
                return i;
            }
        }
        return -1;
    }

    /** 按 \n 切成行（保留其它内容，交给调用方改完再 join） */
    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split("\n", -1);
        for (String part : parts) {
            out.add(part.endsWith("\r") ? part.substring(0, part.length() - 1) : part);
        }
        return out;
    }

    public static String join(List<String> lines) {
        return String.join("\n", lines);
    }
}
