package cn.shijiu.minigamevote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config.toml 的内存快照。每次 /votegameadmin reload 重新解析一份。
 *
 * <p>⚠ 开启方式只有一种：调 MCSManager 面板接口启动实例。
 * 早期那套「自己写个状态接口、用 status-match 匹配响应文本」的通用 HTTP 方案已删除——
 * 状态判定现在统一看 MCSM 返回的实例状态码（见 {@code mcsm.busy-status}）。
 */
public final class VoteConfig {

    /** &a / &#RRGGBB 这类颜色码，取纯文本时去掉 */
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)&(#[0-9a-f]{6}|[0-9a-fk-or])");

    /** ${env:NAME} 或 ${env:NAME:-默认值} */
    private static final Pattern ENV_TOKEN = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\\}");

    public static final class Game {
        public final String id;
        public final String name;

        /** MCSManager 实例 uuid：达标后就是启动这个实例 */
        public final String uuid;

        /** 该实例所在的守护进程 id，留空则用 [mcsm] 里的 daemon-id */
        public final String daemon;

        /**
         * 子服自己的地址 {@code host:port}，用来查「里面还有没有人」。
         *
         * <p>⚠ 必须是<b>子服本体</b>的端口，不是代理端口 —— 填代理会拿到全服总人数。
         * 留空 = 无法确认人数，此时按"还有人在玩"处理（不会去关它）。
         */
        public final String address;

        Game(String id, String name, String uuid, String daemon, String address) {
            this.id = id;
            this.name = name;
            this.uuid = uuid;
            this.daemon = daemon;
            this.address = address;
        }
    }

    /** 每投一票的播报范围 */
    public enum Audience {
        /** 只发给已经投过票的玩家（默认）：没投票的人不被打扰 */
        VOTERS,
        /** 全服广播 */
        ALL,
        /** 不播报 */
        NONE
    }

    /**
     * [mcsm]：直接调 MCSManager 面板接口开/查实例。
     *
     * <p>路径与认证方式写死在 {@link Mcsm} 里（只对接当前版本面板），
     * 这里只留真正需要服主填的东西。
     */
    public static final class McsmSection {
        public final boolean enabled;
        public final String url;
        public final String daemonId;
        public final String apikey;
        public final Set<Integer> busyStatus;

        /** 查询失败（超时/连不上）时算空闲还是算占用 */
        public final boolean errorMeansBusy;

        /** 除小游戏实例外，还要算作"占用"的实例 uuid */
        public final List<String> watch;

        /** 发关闭请求后，等多久算「已经关了」（毫秒）。超时就按已关闭处理，继续开下一个 */
        public final long stopTimeoutMs;

        /** 等待关闭期间，每隔多久查一次实例状态（毫秒） */
        public final long stopPollMs;

        /** 查子服人数的超时（毫秒）；查不到 = 不知道有多少人，按"有人"处理 */
        public final long pingTimeoutMs;

        McsmSection(boolean enabled, String url, String daemonId, String apikey,
                    Set<Integer> busyStatus, boolean errorMeansBusy, List<String> watch,
                    long stopTimeoutMs, long stopPollMs, long pingTimeoutMs) {
            this.enabled = enabled;
            this.url = url;
            this.daemonId = daemonId;
            this.apikey = apikey;
            this.busyStatus = busyStatus;
            this.errorMeansBusy = errorMeansBusy;
            this.watch = watch;
            this.stopTimeoutMs = stopTimeoutMs;
            this.stopPollMs = stopPollMs;
            this.pingTimeoutMs = pingTimeoutMs;
        }
    }

    /** [http]：和面板通信的网络参数（不论查状态还是开实例都走这里） */
    public static final class Http {
        public final int timeoutMs;
        public final String userAgent;

        Http(int timeoutMs, String userAgent) {
            this.timeoutMs = timeoutMs;
            this.userAgent = userAgent;
        }
    }

    public static final class Menu {
        public final boolean enabled;
        public final String openCommand;
        public final boolean reopenAfterVote;
        /** 票数一变就主动刷新所有正开着菜单的玩家（软依赖 TrMenu，装了才生效） */
        public final boolean autoRefresh;
        /** 要刷新的 TrMenu 菜单 id，对应 menus/<id>.yml 的文件名 */
        public final String menuId;
        /**
         * 兜底巡检间隔（tick）：定时比对票数快照，变了就补刷一次菜单。
         * 0 = 关。用来兜住「某条改票数的路径忘了调刷新」这类漏网之鱼
         * （比如玩家退出扣票、管理员重置），正常情况用不上。
         */
        public final int watchdogTicks;
        /**
         * 兜底重开：主动刷新接不上（TrMenu 版本对不上等）时的最后手段 ——
         * 对"当前打开的界面标题里含这个关键词"的玩家重新开一次菜单。
         * 空 = 关。会闪一下，但票数是准的，总比不动强。
         */
        public final String fallbackReopenTitle;

        Menu(boolean enabled, String openCommand, boolean reopenAfterVote,
             boolean autoRefresh, String menuId, int watchdogTicks, String fallbackReopenTitle) {
            this.enabled = enabled;
            this.openCommand = openCommand;
            this.reopenAfterVote = reopenAfterVote;
            this.autoRefresh = autoRefresh;
            this.menuId = menuId;
            this.watchdogTicks = watchdogTicks;
            this.fallbackReopenTitle = fallbackReopenTitle;
        }
    }

    /** 配置里缺项时的兜底文案，避免服主删了某行就 NPE。 */
    private static final Map<String, String> DEFAULT_MESSAGES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prefix", "&6[小游戏投票] &r");
        m.put("vote-success", "&a你投给了 &f%game% &a（%votes%/%threshold%）");
        m.put("vote-changed", "&a你改投给了 &f%game% &a（%votes%/%threshold%），原 &7%old%&a 的一票已取消");
        m.put("already-voted", "&c你当前已经投给 &f%game%&c 了，换一个就自动改票");
        m.put("vote-cancelled", "&a已取消你投给 &f%game% &a的那一票，可以重新投一个");
        m.put("cancel-none", "&c你还没有投过票，没什么可取消的");
        m.put("unknown-game", "&c未知的小游戏：&f%id%");
        m.put("progress", "&7%player% &f投给了 &e%game% &7（%votes%/%threshold%）");
        m.put("progress-changed", "&7%player% &f改投给了 &e%game% &7（%votes%/%threshold%）");
        m.put("no-permission", "&c你没有投票权限");
        m.put("usage", "&7用法：&a/votegame &7打开菜单，&a/votegame <id> &7直接投票");
        m.put("stats-header", "&6========== 小游戏投票 ==========");
        m.put("stats-line", "&7#%rank% &f%game% &7：&e%votes%&7/&e%threshold%");
        m.put("stats-total", "&7已投票人数：&e%total%");
        m.put("stats-none", "&7还没有人投票");
        m.put("threshold-reached", "&f%game% &e达到 &c%threshold% &e票，正在开启…（HTTP %code%）");
        m.put("open-failed", "&f%game% &e达到 &c%threshold% &e票，但开启请求失败（HTTP %code%），票数已重置");
        m.put("busy", "&c已经有小游戏正在进行中（%detail%），本轮投票作废，票数已重置！");
        m.put("self-running", "&e%game% &c已经在进行了 —— 本轮投票作废，票数已重置");
        m.put("other-occupied", "&c%other% &e还在进行，而且里面还有 &a%players% &e人在玩 —— 本轮投票作废，票数已重置");
        m.put("other-unknown", "&c%other% &e还在进行，但查不到里面的人数（没配 address 或 ping 不通），不敢擅自关服 —— 本轮投票作废，票数已重置");
        m.put("closing-others", "&e%game% &a达到 &c%threshold% &a票；&c%other% &e已经没人了，正在关闭…");
        m.put("stop-failed", "&c关闭 &f%other% &c的请求失败了（HTTP %code%），继续等待…");
        m.put("others-closed", "&a%other% &7已全部关闭，正在开启 &f%game%&7…");
        m.put("close-timeout", "&e等 &f%other% &e关闭超时（%seconds% 秒），按已关闭处理，继续开启 &f%game%");
        m.put("no-uuid", "&f%game% &e达到 &c%threshold% &e票，但该小游戏没配实例 uuid（或 MCSM 已关闭），仅重置票数");
        m.put("reset", "&a已重置所有票数与投票记录");
        m.put("diag-trmenu", "&7菜单主动刷新：%state% &7（菜单 &f%menu%&7，auto-refresh=%auto%）");
        m.put("diag-last", "&7上次刷新：&f%ago% &7前（&a%players%&7 人 / &a%icons%&7 个图标），累计 &f%count%&7 次");
        m.put("diag-never", "&7上次刷新：&c从来没有（一次都没成功刷过）");
        m.put("diag-error", "&c最近一次问题：&f%error%");
        m.put("diag-ok", "&a已接入");
        m.put("diag-no-plugin", "&c没检测到 TrMenu");
        m.put("diag-failed", "&c接入失败");
        m.put("diag-off", "&c配置里关了");
        m.put("reloaded", "&a配置已重载（票数已清空）");
        m.put("threshold-set", "&a当前阈值：&e%threshold%");
        m.put("threshold-written", "&a已写回 config.toml 第 &f%line% &a行：&7%after%");
        m.put("threshold-uncommented", "&e⚠ 原来那行是注释（# 开头），已就地取消注释 —— 之前插件根本读不到它");
        m.put("threshold-added", "&e⚠ 配置里原来没有 threshold 这一项，已在顶层新增一行");
        m.put("threshold-write-failed", "&c写回 config.toml 失败：&f%error% &c（内存里的值也没改）");
        m.put("diag-config-file", "&7配置文件：&f%file%");
        m.put("diag-config-line", "&7第 &f%line% &7行：&f%raw%");
        m.put("diag-config-missing", "&c文件里没找到 threshold 行 —— 插件在用默认值 &f%value%");
        m.put("diag-config-commented", "&c那行被 # 注释掉了，插件读不到，用的是默认值 &f%value%");
        m.put("diag-config-match", "&7插件读到的值：&a%value% &7（跟文件一致）");
        m.put("diag-config-mismatch", "&7插件读到的值：&c%value% &7，文件里是 &f%disk% &7—— 跑 &a/votegameadmin reload &7重读");
        m.put("diag-config-mtime", "&7文件最后修改：&f%ago% &7前");
        m.put("diag-config-keep", "&7改阈值不会清空已投的票（调低后已达标的会立刻进开服流程）");
        m.put("opened-menu", "&a已为 &f%player% &a打开投票菜单");
        m.put("test-result", "&7状态码：&e%code% &7(ok=%ok%)");
        m.put("test-body", "&7响应：&f%body%");
        m.put("diag-ping-ok", "&7- &f%game% &7(%addr%)：&a%players% &7人");
        m.put("diag-ping-fail", "&7- &f%game% &7(%addr%)：&c查不到（连不上 / 超时 / 地址写错）");
        m.put("diag-ping-none", "&7- &f%game% &7：&c没配 address（查不到人数 → 不会被关）");
        m.put("test-probe", "&7读到的实例状态：&f%list%");
        m.put("test-probe-bad", "&c读不出实例状态：&f%reason% &7（uuid 不对？没权限？看上面的响应原文）");
        DEFAULT_MESSAGES = Collections.unmodifiableMap(m);
    }

    public final int threshold;
    public final LinkedHashMap<String, Game> games;
    public final Http http;
    public final McsmSection mcsm;
    public final Menu menu;
    public final Audience audience;
    public final String soundVote;
    public final String soundDenied;

    /** 配置里引用到的环境变量名（只存名字，不存值） */
    public final Set<String> envNames;

    /** 引用了但系统里没设置的环境变量名 */
    public final Set<String> envMissing;

    private final Map<String, String> messages;

    private VoteConfig(int threshold, LinkedHashMap<String, Game> games,
                       Http http, McsmSection mcsm, Menu menu, Audience audience,
                       String soundVote, String soundDenied,
                       Map<String, String> messages,
                       Set<String> envNames, Set<String> envMissing) {
        this.threshold = threshold;
        this.games = games;
        this.http = http;
        this.mcsm = mcsm;
        this.menu = menu;
        this.audience = audience;
        this.soundVote = soundVote;
        this.soundDenied = soundDenied;
        this.messages = messages;
        this.envNames = envNames;
        this.envMissing = envMissing;
    }

    /** @param t Toml.parse() 出来的扁平表 */
    public static VoteConfig load(Map<String, Object> t) {
        Set<String> envNames = new LinkedHashSet<>();
        Set<String> envMissing = new LinkedHashSet<>();

        // ① 小游戏：所有 games.<id>.name 各算一个
        LinkedHashMap<String, Game> games = new LinkedHashMap<>();
        for (String key : t.keySet()) {
            if (!key.startsWith("games.") || !key.endsWith(".name")) {
                continue;
            }
            String id = key.substring("games.".length(), key.length() - ".name".length());
            if (id.isEmpty() || id.contains(".")) {
                continue;
            }
            id = id.toLowerCase(Locale.ROOT);
            String name = Toml.string(t, "games." + id + ".name", id);
            String uuid = env(Toml.string(t, "games." + id + ".uuid", ""), envNames, envMissing).trim();
            String daemon = env(Toml.string(t, "games." + id + ".daemon-id", ""), envNames, envMissing).trim();
            String address = env(Toml.string(t, "games." + id + ".address", ""), envNames, envMissing).trim();
            games.put(id, new Game(id, name, uuid, daemon, address));
        }

        // ② MCSManager：开/查面板上的实例
        List<String> watch = new ArrayList<>();
        for (String w : Toml.list(t, "mcsm.watch")) {
            String v = env(w.trim(), envNames, envMissing);
            if (!v.isEmpty()) {
                watch.add(v);
            }
        }
        McsmSection mcsm = new McsmSection(
                Toml.bool(t, "mcsm.enabled", true),
                env(Toml.string(t, "mcsm.url", ""), envNames, envMissing).trim(),
                env(Toml.string(t, "mcsm.daemon-id", ""), envNames, envMissing).trim(),
                secret(Toml.string(t, "mcsm.apikey", ""), envNames, envMissing).trim(),
                intSet(Toml.list(t, "mcsm.busy-status"), 2, 3),
                "busy".equalsIgnoreCase(Toml.string(t, "mcsm.on-query-error", "idle")),
                watch,
                // 等其它实例关完的超时（默认 5 分钟）与轮询间隔（默认 3 秒）
                Math.max(1000L, Toml.integer(t, "mcsm.stop-timeout-ms", 300000)),
                Math.max(500L, Toml.integer(t, "mcsm.stop-poll-ms", 3000)),
                Math.max(200L, Toml.integer(t, "mcsm.ping-timeout-ms", 1500))
        );

        Http http = new Http(
                Math.max(500, Toml.integer(t, "http.timeout-ms", 3000)),
                Toml.string(t, "http.user-agent", "ShijiuWorld-MiniGameVote")
        );

        Menu menu = new Menu(
                Toml.bool(t, "menu.enabled", true),
                env(Toml.string(t, "menu.open-command", "trm open minigamevote %player%"), envNames, envMissing),
                Toml.bool(t, "menu.reopen-after-vote", true),
                Toml.bool(t, "menu.auto-refresh", true),
                Toml.string(t, "menu.menu-id", "minigamevote"),
                // 兜底巡检：票数快照变了就补刷一次（0 = 关）。默认 30 秒，开销可忽略
                Math.max(0, Toml.integer(t, "menu.watchdog-ticks", 600)),
                Toml.string(t, "menu.fallback-reopen-title", "")
        );

        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : t.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("messages.") && e.getValue() != null) {
                messages.put(key.substring("messages.".length()), String.valueOf(e.getValue()));
            }
        }

        return new VoteConfig(
                Math.max(1, Toml.integer(t, "threshold", 5)),
                games, http, mcsm, menu,
                audience(Toml.string(t, "progress-audience", "voters")),
                Toml.string(t, "sounds.vote", "ENTITY_PLAYER_LEVELUP"),
                Toml.string(t, "sounds.denied", "ENTITY_VILLAGER_NO"),
                messages, envNames, envMissing
        );
    }

    /**
     * 把 ${env:NAME}（或 ${env:NAME:-默认值}）换成环境变量的值。
     *
     * <p>取不到且没写默认值 → 置空并记进 missing（插件加载完会打警告）。
     * 绝不保留 {@code ${env:xxx}} 字面量：密钥万一没配，宁可发空也不要把占位符原样发给面板。
     */
    private static String env(String text, Set<String> names, Set<String> missing) {
        return env(text, System::getenv, names, missing);
    }

    /** 和上面一样，但取值来源可换（测试时不用真去设系统环境变量） */
    static String env(String text, Function<String, String> lookup, Set<String> names, Set<String> missing) {
        if (text == null || text.isEmpty() || text.indexOf("${env:") < 0) {
            return text == null ? "" : text;
        }
        Matcher m = ENV_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            if (names != null) {
                names.add(name);
            }
            String v = lookup == null ? null : lookup.apply(name);
            if (v == null) {
                v = m.group(2);                 // ${env:X:-默认值} 里的默认值
                if (v == null) {
                    v = "";
                    if (missing != null) {
                        missing.add(name);
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 密钥字段专用：先按环境变量规则取值，再决定要不要解密。
     *
     * <p>⚠ 只有「直接写在 config.toml 里的字面量」才走解密 ——
     * {@code ${env:XXX}} 从环境变量取回来的值原样使用，不去解。
     * 理由：环境变量本来就没落在文件里，再解一次只会把一个好好的明文解成空串。
     * 字面量里带 {@code ENC1:} 前缀的才当密文解，没前缀的（普通明文）也原样用。
     */
    private static String secret(String text, Set<String> names, Set<String> missing) {
        return secret(text, System::getenv, names, missing);
    }

    /** 同上，但取值来源可换（测试时不用真去设系统环境变量） */
    static String secret(String text, Function<String, String> lookup, Set<String> names, Set<String> missing) {
        String v = env(text, lookup, names, missing);
        if (v == null) {
            return "";
        }
        boolean fromEnv = text != null && text.indexOf("${env:") >= 0;
        return fromEnv ? v : KeyCrypto.decrypt(v);
    }

    /** 播报范围：voters（默认）/ all / none，写别的或写歪都按 voters */
    private static Audience audience(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "all":
            case "everyone":
                return Audience.ALL;
            case "none":
            case "off":
            case "false":
                return Audience.NONE;
            default:
                return Audience.VOTERS;
        }
    }

    /** 把 ["2","3"] 这样的配置读成状态码集合；没配或全写歪了就用默认值 */
    private static Set<Integer> intSet(List<String> raw, int... def) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String s : raw) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // 写歪的那项跳过
            }
        }
        if (out.isEmpty()) {
            for (int d : def) {
                out.add(d);
            }
        }
        return out;
    }

    /** 取一条消息原文（含 & 颜色码）；配置里没有就用内置默认。 */
    public String msg(String key) {
        String v = messages.get(key);
        if (v == null || v.isEmpty()) {
            v = DEFAULT_MESSAGES.get(key);
        }
        return v == null ? "" : v;
    }

    public Game game(String id) {
        return id == null ? null : games.get(id.toLowerCase(Locale.ROOT));
    }

    /** 显示名（去掉颜色码），给占位符这类纯文本场合用 */
    public String plainName(String id) {
        Game g = game(id);
        if (g == null) {
            return id;
        }
        return COLOR_CODE.matcher(g.name).replaceAll("");
    }
}
