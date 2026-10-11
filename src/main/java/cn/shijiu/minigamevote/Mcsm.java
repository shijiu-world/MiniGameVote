package cn.shijiu.minigamevote;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCSManager 接口的拼装与解读。
 *
 * <p>只对接当前版本 MCSManager，用到的两个接口
 * （见 https://docs.mcsmanager.com/zh_cn/apis/api_instance.html）：
 * <ul>
 *   <li><b>开实例</b>：{@code GET /api/protected_instance/open?uuid=&daemonId=}</li>
 *   <li><b>查状态</b>：{@code GET /api/instance?uuid=&daemonId=}，每个小游戏实例查一次</li>
 * </ul>
 * 认证按官方文档来（docs.mcsmanager.com → apis → get_apikey.md）：
 * <ul>
 *   <li>apikey 拼在 <b>URL 参数</b>上：{@code ?uuid=&daemonId=&apikey=}</li>
 *   <li>两个<b>必需</b>请求头：{@code X-Requested-With: XMLHttpRequest}、
 *       {@code Content-Type: application/json; charset=utf-8}（缺了会被判成非法请求，直接 403）</li>
 *   <li>额外再带 {@code Authorization: Bearer <apikey>}：面板同样认，纯冗余保险</li>
 * </ul>
 * ⚠ apikey 进了 URL，意味着凡是打印 URL 的地方都必须先过 {@link #mask(String, String)}，
 * 否则一条广播/日志就把密钥漏出去了。
 *
 * <p>状态码含义（文档里的 InstanceDetail.status）：
 * <pre>
 *   -1 忙碌 / 0 停止 / 1 停止中 / 2 启动中 / 3 运行中
 * </pre>
 * 默认把「启动中」和「运行中」都算作"有小游戏在跑"，所以投票作废。
 *
 * <p>⚠ 路径与认证方式写死在这里，不提供配置开关：老版本面板的兼容分支已移除。
 * 逐个查而不是"一次列全部"，是因为 {@code /api/instance} 是最基础的接口，
 * 不依赖分页列表（列表接口的路径名在面板版本间变过），而且单个实例查失败
 * 不影响其它实例，配合 {@code on-query-error} 更稳。
 */
public final class Mcsm {

    /** 启动实例 */
    private static final String PATH_OPEN = "/api/protected_instance/open";

    /** 查询单个实例详情 */
    private static final String PATH_DETAIL = "/api/instance";

    /** 停止实例（先关掉还在跑的另一个小游戏，才能开新的） */
    private static final String PATH_STOP = "/api/protected_instance/stop";

    private final VoteConfig c;

    public Mcsm(VoteConfig c) {
        this.c = c;
    }

    // ------------------------------------------------------------------
    // 地址
    // ------------------------------------------------------------------

    private String url(String path, LinkedHashMap<String, String> params) {
        StringBuilder sb = new StringBuilder(c.mcsm.url.trim());
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        sb.append(path.startsWith("/") ? path : "/" + path);
        sb.append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        // 官方文档的鉴权方式：apikey 作为 URL 参数
        if (c.mcsm.apikey != null && !c.mcsm.apikey.isEmpty()) {
            if (!first) {
                sb.append('&');
            }
            sb.append("apikey=").append(enc(c.mcsm.apikey));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    /** 某个小游戏的「启动实例」地址 */
    public String openUrl(VoteConfig.Game g) {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("uuid", g.uuid == null ? "" : g.uuid);
        p.put("daemonId", daemonOf(g));
        return url(PATH_OPEN, p);
    }

    /** 某个小游戏的「停止实例」地址 */
    public String stopUrl(VoteConfig.Game g) {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("uuid", g.uuid == null ? "" : g.uuid);
        p.put("daemonId", daemonOf(g));
        return url(PATH_STOP, p);
    }

    /** 单个实例的状态查询地址（等待关闭时只查那几个） */
    public String statusUrl(VoteConfig.Game g) {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("uuid", g.uuid == null ? "" : g.uuid);
        p.put("daemonId", daemonOf(g));
        return url(PATH_DETAIL, p);
    }

    /** 0 = 已停止，这才算「关完了」（1 = 停止中，还得再等等） */
    public static boolean isStopped(int status) {
        return status == 0;
    }

    /**
     * 查状态用的地址：每个配了 uuid 的小游戏一条，**去重**。
     *
     * <p>去重是必要的：配置模板里 6 个游戏的 uuid 都是同一个占位值，服主没改全的话
     * 不去重就会拿同一个假 uuid 查 6 次，白白多花 6 个请求。
     */
    public List<String> statusUrls() {
        List<String> out = new ArrayList<>();
        for (VoteConfig.Game g : c.games.values()) {
            if (g.uuid == null || g.uuid.isEmpty()) {
                continue;
            }
            LinkedHashMap<String, String> p = new LinkedHashMap<>();
            p.put("uuid", g.uuid);
            p.put("daemonId", daemonOf(g));
            String u = url(PATH_DETAIL, p);
            if (!out.contains(u)) {
                out.add(u);
            }
        }
        return out;
    }

    /** 看着不像 MCSM 实例 uuid（32 位十六进制）就返回 false */
    public static boolean looksLikeUuid(String s) {
        return s != null && s.matches("(?i)[0-9a-f]{32}");
    }

    private String daemonOf(VoteConfig.Game g) {
        String d = g.daemon;
        if (d == null || d.isEmpty()) {
            d = c.mcsm.daemonId;
        }
        return d == null ? "" : d;
    }

    /**
     * 请求头。前两个是官方文档点名的<b>必需</b>头，缺了面板直接回 403（不是 401）；
     * 第三个 {@code Authorization} 是冗余保险 —— 面板同样认，写了不为过。
     */
    public Map<String, String> authHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Content-Type", "application/json; charset=utf-8");
        if (!c.mcsm.apikey.isEmpty()) {
            h.put("Authorization", "Bearer " + c.mcsm.apikey);
        }
        return h;
    }

    /** 把文本里的密钥换成 *** —— URL 带上 apikey 后，任何输出 URL 的地方都得先过这里 */
    public static String mask(String text, String secret) {
        if (text == null) {
            return "";
        }
        if (secret == null || secret.isEmpty()) {
            return text;
        }
        return text.replace(secret, "***");
    }

    // ------------------------------------------------------------------
    // 响应解读
    // ------------------------------------------------------------------

    /** 需要盯着的实例：所有小游戏实例 + 配置里额外写的 uuid */
    public Set<String> watched() {
        Set<String> out = new LinkedHashSet<>();
        for (VoteConfig.Game g : c.games.values()) {
            if (g.uuid != null && !g.uuid.isEmpty()) {
                out.add(g.uuid);
            }
        }
        out.addAll(c.mcsm.watch);
        return out;
    }

    /**
     * 从响应里读出「实例 uuid → 状态码」。
     * 现在是逐个查 {@code /api/instance}，正常只会有单条结构；数组结构一并认，
     * 纯粹是为了面板哪天换了返回形态不至于整段解析失败。
     * <pre>
     *   单条：{ "status":200, "data": { "instanceUuid":"x", "status":3 } }
     *   列表：{ "status":200, "data": { "data": [ { "instanceUuid":"x", "status":3 } ] } }
     * </pre>
     * ⚠ 顶层那个 "status":200 是 HTTP 层的状态码，别拿它当实例状态——必须从 data 里取。
     */
    public Map<String, Integer> parseStatuses(String body) {
        return probe(body).statuses;
    }

    /**
     * 一次状态查询的结果。
     *
     * <p>⚠ {@link #valid} 为 false 时 {@link #statuses} 是空的，这时<b>不能当成"实例停着"</b> ——
     * 它只代表"没查到"，真实状态未知。这两件事必须分开，否则：
     * 实例 uuid 写错 / 没权限 / 面板业务码非 200 时，面板照样回 HTTP 200 但 {@code data} 是 null，
     * 旧写法解析出空表 → 循环什么都不做 → 判定"全都空闲" → 对<b>本来就在跑的</b>实例
     * 再发一次开启请求。
     */
    public static final class Probe {
        /** 响应可信：业务码 200 且真的读到了实例状态 */
        public final boolean valid;

        /** 实例 uuid → 状态码；不可信时是空表 */
        public final Map<String, Integer> statuses;

        /** 不可信的原因（写进日志，不含密钥） */
        public final String reason;

        Probe(boolean valid, Map<String, Integer> statuses, String reason) {
            this.valid = valid;
            this.statuses = statuses;
            this.reason = reason;
        }
    }

    /**
     * 解读一次 {@code /api/instance} 的响应。
     *
     * <p>⚠ 只看 HTTP 状态码是不够的：面板在「uuid 不存在 / 没权限 / 参数不对」时
     * 也是回 HTTP 200，区别在<b>业务码</b>和 {@code data} 是不是 null。
     */
    public Probe probe(String body) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Object root = Json.parse(body);
        if (root == null) {
            return new Probe(false, out, "响应不是合法 JSON（或为空）");
        }
        Integer code = Json.integer(Json.at(root, "status"));
        if (code != null && code != 200) {
            return new Probe(false, out, "面板业务码 " + code + "（多半是 uuid 不对或没权限）");
        }
        if (Json.at(root, "data") == null) {
            return new Probe(false, out, "data 为 null（实例 uuid 不对或没权限）");
        }
        List<Object> arr = Json.array(Json.at(root, "data.data"));
        if (arr != null) {
            for (Object item : arr) {
                put(out, item);
            }
        } else {
            put(out, Json.at(root, "data"));
        }
        if (out.isEmpty()) {
            return new Probe(false, out, "响应里没有实例状态字段（instanceUuid / status）");
        }
        return new Probe(true, out, null);
    }

    private void put(Map<String, Integer> out, Object item) {
        if (!(item instanceof Map)) {
            return;
        }
        String uuid = Json.str(Json.at(item, "instanceUuid"));
        Integer st = Json.integer(Json.at(item, "status"));
        if (uuid != null && !uuid.isEmpty() && st != null) {
            out.put(uuid, st);
        }
    }

    /** 这个实例算不算「正在跑」（只看被盯着的实例，状态码在 busy-status 里） */
    public boolean isBusy(String uuid, Integer status) {
        if (uuid == null || status == null) {
            return false;
        }
        return watched().contains(uuid) && c.mcsm.busyStatus.contains(status);
    }

    /** uuid → 小游戏显示名，用来把「哪个实例在跑」说人话 */
    public String displayName(String uuid) {
        for (VoteConfig.Game g : c.games.values()) {
            if (uuid.equals(g.uuid)) {
                return c.plainName(g.id);
            }
        }
        return uuid;
    }

    public static String statusText(int s) {
        switch (s) {
            case -1: return "忙碌";
            case 0: return "停止";
            case 1: return "停止中";
            case 2: return "启动中";
            case 3: return "运行中";
            default: return "未知(" + s + ")";
        }
    }
}
