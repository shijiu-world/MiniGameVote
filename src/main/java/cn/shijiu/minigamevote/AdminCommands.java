package cn.shijiu.minigamevote;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /votegameadmin —— reload / reset / stats / threshold / open / test / version */
public final class AdminCommands implements TabExecutor {

    private final MiniGameVote plugin;

    public AdminCommands(MiniGameVote plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        VoteConfig c = plugin.config();

        if (args.length == 0) {
            Msg.send(sender, c.msg("prefix") + "&7用法：&a/votegameadmin reload|reset|stats|threshold <数>|open|test [url]|env|mcsm|trmenu|config|version");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload": {
                plugin.reload();
                Msg.send(sender, c.msg("prefix") + plugin.config().msg("reloaded"));
                return true;
            }
            case "reset": {
                plugin.votes().reset();
                plugin.flow().forceUnlock();
                plugin.refreshMenus();
                Msg.send(sender, c.msg("prefix") + c.msg("reset"));
                return true;
            }
            case "stats": {
                plugin.sendStats(sender);
                return true;
            }
            case "threshold": {
                if (args.length >= 2) {
                    try {
                        int n = Integer.parseInt(args[1]);
                        if (n < 1) {
                            Msg.send(sender, c.msg("prefix") + "&c阈值至少是 1");
                            return true;
                        }
                        ConfigEdit.Result r = plugin.setThreshold(n);
                        if (!r.ok) {
                            Msg.send(sender, c.msg("prefix")
                                    + Msg.fill(c.msg("threshold-write-failed"), "error", String.valueOf(r.error)));
                            return true;
                        }
                        c = plugin.config();
                        // 把"改了文件哪一行"直接回显，省得服主猜到底写没写进去
                        Msg.send(sender, c.msg("prefix") + Msg.fill(c.msg("threshold-written"),
                                "line", String.valueOf(r.line),
                                "before", r.before.isEmpty() ? "（无）" : r.before,
                                "after", r.after));
                        if (r.uncommented) {
                            Msg.send(sender, c.msg("prefix") + c.msg("threshold-uncommented"));
                        }
                        if (r.added) {
                            Msg.send(sender, c.msg("prefix") + c.msg("threshold-added"));
                        }
                    } catch (NumberFormatException e) {
                        Msg.send(sender, c.msg("prefix") + "&c阈值得是数字");
                        return true;
                    }
                }
                Msg.send(sender, c.msg("prefix") + Msg.fill(c.msg("threshold-set"), "threshold", String.valueOf(c.threshold)));
                Msg.send(sender, c.msg("prefix") + c.msg("diag-config-keep"));
                showConfigLine(sender, c);
                return true;
            }
            case "open": {
                int n = 0;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.openMenu(p);
                    n++;
                }
                Msg.send(sender, c.msg("prefix") + Msg.fill(c.msg("opened-menu"), "player", n + " 名玩家"));
                return true;
            }
            case "test": {
                String url;
                Map<String, String> hdrs = Collections.emptyMap();
                if (args.length >= 2) {
                    url = args[1];
                } else {
                    Mcsm m = new Mcsm(c);
                    List<String> us = m.statusUrls();
                    url = us.isEmpty() ? "" : us.get(0);
                    hdrs = m.authHeaders();
                }
                if (url.isEmpty()) {
                    Msg.send(sender, c.msg("prefix") + "&c没给地址，而且没有小游戏配了实例 uuid，拼不出查询地址");
                    return true;
                }
                // apikey 只走请求头，不该出现在 URL 里；打码是双保险
                Msg.send(sender, c.msg("prefix") + "&7已发起异步请求：&f" + Mcsm.mask(url, c.mcsm.apikey));
                final CommandSender target = sender;
                final VoteConfig cfg = c;
                plugin.http().request(url, "GET", null, c.http.timeoutMs, c.http.userAgent, hdrs)
                        .whenComplete((r, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (target instanceof Player && !((Player) target).isOnline()) {
                                return;
                            }
                            String code = r == null ? "-1" : String.valueOf(r.code);
                            String ok = r == null ? "false" : String.valueOf(r.ok);
                            String body = r == null ? "" : r.body;
                            if (body.length() > 300) {
                                body = body.substring(0, 300) + "…（已截断）";
                            }
                            Msg.send(target, cfg.msg("prefix") + Msg.fill(cfg.msg("test-result"), "code", code, "ok", ok));
                            Msg.send(target, cfg.msg("prefix") + Msg.fill(cfg.msg("test-body"), "body", body));
                            // 顺手把"插件从这段响应里读出了什么"显示出来：
                            // 读不出实例状态时，多半是 uuid 不对 / 没权限 / 业务码非 200
                            Mcsm svc = new Mcsm(cfg);
                            Mcsm.Probe probe = svc.probe(r == null ? "" : r.body);
                            if (probe.valid) {
                                StringBuilder sb = new StringBuilder();
                                for (Map.Entry<String, Integer> e : probe.statuses.entrySet()) {
                                    if (sb.length() > 0) {
                                        sb.append("、");
                                    }
                                    sb.append(svc.displayName(e.getKey())).append(" ")
                                            .append(Mcsm.statusText(e.getValue()))
                                            .append(svc.isBusy(e.getKey(), e.getValue()) ? "（算占用）" : "（算空闲）");
                                }
                                Msg.send(target, cfg.msg("prefix") + Msg.fill(cfg.msg("test-probe"), "list", sb.toString()));
                            } else {
                                Msg.send(target, cfg.msg("prefix") + Msg.fill(cfg.msg("test-probe-bad"), "reason", probe.reason));
                            }
                        }));
                return true;
            }
            case "env": {
                VoteConfig cfg = plugin.config();
                Msg.send(sender, cfg.msg("prefix") + "&7配置引用到的环境变量 &a" + cfg.envNames.size() + " &7个（不显示值）：");
                if (cfg.envNames.isEmpty()) {
                    Msg.send(sender, "&7  （没有用到 ${env:xxx}，apikey 目前没走环境变量）");
                }
                for (String n : cfg.envNames) {
                    boolean bad = cfg.envMissing.contains(n);
                    Msg.send(sender, "&7- &e" + n + " &7→ " + (bad ? "&c没读到（按空值处理）" : "&a已读取"));
                }
                if (!cfg.envMissing.isEmpty()) {
                    Msg.send(sender, "&7环境变量要在「开服脚本里 export」再启动服务端；补好后 &a/votegameadmin reload &7重读");
                }
                return true;
            }
            case "mcsm": {
                VoteConfig cfg = plugin.config();
                VoteConfig.McsmSection m = cfg.mcsm;
                Mcsm svc = new Mcsm(cfg);
                Msg.send(sender, cfg.msg("prefix") + "&7MCSM 模式：&a" + (m.enabled ? "已启用" : "已关闭（只计票不联网）"));
                Msg.send(sender, "&7- 面板地址：&f" + (m.url.isEmpty() ? "&c未配置" : m.url));
                Msg.send(sender, "&7- 守护进程：&f" + (m.daemonId.isEmpty() ? "&c未配置" : m.daemonId));
                Msg.send(sender, "&7- apikey：&f" + (m.apikey.isEmpty()
                        ? "&c没读到（看后台 WARNING 或 /votegameadmin env）"
                        : "&a已设置（长度 " + m.apikey.length() + "，不显示内容）"));
                Msg.send(sender, "&7- 认证方式：&fURL 参数 &aapikey=…&f（打码显示）+ 必需头"
                        + " &aX-Requested-With: XMLHttpRequest&f、&aContent-Type: application/json"
                        + " &7（面板文档要求；另附 Authorization: Bearer 冗余）");
                Msg.send(sender, "&7- 状态查询：&f每个小游戏实例查一次 /api/instance");
                StringBuilder bs = new StringBuilder();
                for (int s : m.busyStatus) {
                    if (bs.length() > 0) {
                        bs.append("、");
                    }
                    bs.append(s).append("(").append(Mcsm.statusText(s)).append(")");
                }
                Msg.send(sender, "&7- 算「正在跑」的状态：&f" + bs);
                Msg.send(sender, "&7- 查询失败时：&f" + (m.errorMeansBusy ? "算占用（不开服）" : "算空闲（继续开服）"));
                Msg.send(sender, cfg.msg("prefix") + "&7小游戏 → 实例：");
                for (VoteConfig.Game g : cfg.games.values()) {
                    Msg.send(sender, "&7- &f" + cfg.plainName(g.id) + " &7→ "
                            + (g.uuid.isEmpty() ? "&c没配 uuid" : g.uuid
                            + (g.daemon.isEmpty() ? "" : " &7@ " + g.daemon)));
                }
                Msg.send(sender, cfg.msg("prefix") + "&7状态查询地址（apikey 已打码）：");
                List<String> us = svc.statusUrls();
                if (us.isEmpty()) {
                    Msg.send(sender, "&7  （空：没有小游戏配了 uuid）");
                }
                for (String u : us) {
                    Msg.send(sender, "&7- &f" + Mcsm.mask(u, m.apikey));
                }
                if (!m.watch.isEmpty()) {
                    Msg.send(sender, cfg.msg("prefix") + "&7额外监视的实例：&f" + m.watch.size() + " 个");
                }
                return true;
            }
            case "trmenu": {
                VoteConfig cfg = plugin.config();
                VoteConfig.Menu m = cfg.menu;
                boolean installed = plugin.getServer().getPluginManager().isPluginEnabled("TrMenu");
                TrMenuHook hook = plugin.trMenu();

                // 先问 hook 再问面板：TrMenu 可能是后加载的，enabled 判断会滞后
                String state;
                if (!m.autoRefresh) {
                    state = cfg.msg("diag-off");
                } else if (hook != null && hook.available()) {
                    state = cfg.msg("diag-ok");
                } else if (!installed) {
                    state = cfg.msg("diag-no-plugin");
                } else {
                    state = cfg.msg("diag-failed");
                }
                Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-trmenu"),
                        "state", state,
                        "menu", m.menuId,
                        "auto", String.valueOf(m.autoRefresh)));
                if (hook != null) {
                    long at = hook.lastRefreshAt();
                    if (at <= 0) {
                        Msg.send(sender, cfg.msg("prefix") + cfg.msg("diag-never"));
                    } else {
                        long sec = Math.max(0, (System.currentTimeMillis() - at) / 1000);
                        String ago = sec < 60 ? sec + " 秒" : (sec / 60) + " 分" + (sec % 60) + " 秒";
                        Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-last"),
                                "ago", ago,
                                "players", String.valueOf(hook.lastPlayers()),
                                "icons", String.valueOf(hook.lastIcons()),
                                "count", String.valueOf(hook.refreshCount())));
                    }
                    String err = hook.lastError();
                    if (err != null && !err.isEmpty()) {
                        Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-error"), "error", err));
                    }
                }
                Msg.send(sender, "&7兜底巡检：&f" + (m.watchdogTicks > 0
                        ? "每 " + (m.watchdogTicks / 20) + " 秒比对一次票数快照，变了就补刷"
                        : "已关闭（menu.watchdog-ticks = 0）"));
                if (!m.autoRefresh) {
                    Msg.send(sender, "&7把 config.toml 里 [menu] auto-refresh 改成 true 再 reload");
                    Msg.send(sender, "&7不装这个也行：给菜单图标加一行 update: 40 自己轮询");
                } else if (installed && (hook == null || !hook.available())) {
                    Msg.send(sender, "&c接入失败多半是 TrMenu 版本对不上 —— 后台日志里有具体异常");
                    Msg.send(sender, "&7临时方案：给菜单每个票数图标加 update: 40");
                }
                return true;
            }
            case "config": {
                VoteConfig cfg = plugin.config();
                Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-config-file"),
                        "file", plugin.configFile().toAbsolutePath().toString()));
                showConfigLine(sender, cfg);
                try {
                    long ms = java.nio.file.Files.getLastModifiedTime(plugin.configFile()).toMillis();
                    long sec = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
                    Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-config-mtime"),
                            "ago", sec < 60 ? sec + " 秒" : (sec / 60) + " 分" + (sec % 60) + " 秒"));
                } catch (IOException ignored) {
                    // 读不到修改时间就算了，不影响前面的信息
                }
                Msg.send(sender, "&7改完文件后要 &a/votegameadmin reload &7才会重新读；"
                        + "面板的文件编辑器会缓存，改完记得刷新再看");
                return true;
            }
            case "version": {
                VoteConfig cur = plugin.config();
                int withUuid = 0;
                for (VoteConfig.Game g : cur.games.values()) {
                    if (!g.uuid.isEmpty()) {
                        withUuid++;
                    }
                }
                Msg.send(sender, cur.msg("prefix") + "&7版本 &a" + plugin.getDescription().getVersion()
                        + " &7| 小游戏 &a" + cur.games.size() + " &7(已配 uuid &a" + withUuid + "&7)"
                        + " &7| 阈值 &a" + cur.threshold
                        + " &7| MCSM &a" + (cur.mcsm.enabled ? "开" : "关")
                        + " &7| 状态查询 &a" + new Mcsm(cur).statusUrls().size() + " &7条"
                        + " &7| 处理中 &a" + (plugin.flow().isResolving() ? "是" : "否"));
                return true;
            }
            default: {
                Msg.send(sender, c.msg("prefix") + "&7用法：&a/votegameadmin reload|reset|stats|threshold <数>|open|test [url]|env|mcsm|trmenu|config|version");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> base = Arrays.asList("reload", "reset", "stats", "threshold", "open", "test", "env", "mcsm", "trmenu", "config", "version");
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : base) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return new ArrayList<>(new Mcsm(plugin.config()).statusUrls());
        }
        return Collections.emptyList();
    }

    /**
     * 把「插件读的那个 config.toml 里 threshold 现在写的啥」显示出来，跟内存值对一下。
     *
     * <p>专治"我在游戏里改了，配置文件看着没变"—— 文件在哪、那行长啥样、
     * 插件实际读到的值是多少，三样一起摆出来。
     */
    private void showConfigLine(CommandSender sender, VoteConfig cfg) {
        ConfigEdit.Found found = plugin.findThresholdLine();
        if (found.missing()) {
            Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-config-missing"),
                    "value", String.valueOf(cfg.threshold)));
            return;
        }
        Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-config-line"),
                "line", String.valueOf(found.line),
                "raw", found.raw));
        if (found.commented) {
            Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-config-commented"),
                    "value", String.valueOf(cfg.threshold)));
            return;
        }
        int onDisk = Toml.integer(Toml.parse(found.raw), "threshold", -1);
        if (onDisk == cfg.threshold) {
            Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-config-match"),
                    "value", String.valueOf(cfg.threshold)));
        } else {
            Msg.send(sender, cfg.msg("prefix") + Msg.fill(cfg.msg("diag-config-mismatch"),
                    "value", String.valueOf(cfg.threshold),
                    "disk", String.valueOf(onDisk)));
        }
    }
}
