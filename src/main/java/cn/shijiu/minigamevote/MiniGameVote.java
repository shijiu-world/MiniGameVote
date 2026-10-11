package cn.shijiu.minigamevote;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * 大厅小游戏投票。
 *
 * 玩法：
 *   玩家 /votegame        → 打开 TrMenu 菜单（plugins/TrMenu/menus/minigamevote.yml）
 *   玩家 /votegame <id>   → 直接投票；一人一票，再投别的即改票（上一票取消）
 *   玩家退出子服          → 他投的票自动作废
 *   某个小游戏票数达标    → 异步查所有实例 → 都空闲才发开启请求 → 重置票数
 */
public final class MiniGameVote extends JavaPlugin {

    private VoteConfig config;
    private VoteManager manager;
    private HttpService http;
    private VoteFlow flow;
    private TrMenuHook trMenu;

    /**
     * 配置代次：每次 reload 加一。
     *
     * <p>用途：HTTP 是异步的，reload 之后旧流程的回调才回来，如果照常执行就会把
     * reload 之后新投的票 reset 掉。回调时比对代次，不一致就丢弃这一轮结果。
     */
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 兜底巡检用的票数快照：所有小游戏票数 + 投票人数拼成的字符串 */
    private volatile String lastVoteSnapshot = "";

    /** 主动刷新不可用时，警告最多 5 分钟一条，免得刷屏 */
    private volatile long lastRefreshWarnAt = 0;

    public int generation() {
        return generation.get();
    }

    private String voteSnapshot() {
        VoteConfig c = config;
        if (c == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : c.games.keySet()) {
            sb.append(id).append('=').append(manager.votesOf(id)).append(';');
        }
        return sb.append("total=").append(manager.totalVoters()).toString();
    }

    @Override
    public void onEnable() {
        // 配置文件是 config.toml（不是 Bukkit 默认的 config.yml），自己复制默认文件
        if (!Files.exists(configFile())) {
            saveResource("config.toml", false);
        }

        this.manager = new VoteManager();
        this.http = new HttpService();
        this.flow = new VoteFlow(this);
        reload();

        VoteCommands voteCommands = new VoteCommands(this);
        AdminCommands adminCommands = new AdminCommands(this);
        bind("votegame", voteCommands);
        bind("votegameadmin", adminCommands);

        getServer().getPluginManager().registerEvents(new VoteListener(this), this);

        // 主动刷新菜单：票数一变就推给所有正开着菜单的人（软依赖，TrMenu 没装就跳过）
        // ⚠ 这里只做"能接就接"，接不上不报错 —— TrMenu 可能是后加载/热加载的，
        //   那种情况下由 TrMenuHook 在第一次刷新时自己再试（懒接入），不会因为启动顺序永久失效。
        this.trMenu = new TrMenuHook(this, getLogger());
        if (config.menu.autoRefresh) {
            if (!getServer().getPluginManager().isPluginEnabled("TrMenu")) {
                getLogger().info("未检测到 TrMenu：菜单只能靠玩家点击时刷新"
                        + "（要所有人实时看到票数，请给菜单图标加 update: 40）");
            } else if (trMenu.prepare()) {
                getLogger().info("已接入 TrMenu：票数变化会主动刷新菜单 " + config.menu.menuId);
            }
        }

        // 兜底巡检：定时比对票数快照，变了就补刷一次。
        // 正常路径（投票/改票/取消/退出/重置）都已经主动刷了，这是为了防止哪条路径漏掉。
        if (config.menu.autoRefresh && config.menu.watchdogTicks > 0) {
            long period = Math.max(20L, config.menu.watchdogTicks);
            lastVoteSnapshot = voteSnapshot();
            getServer().getScheduler().runTaskTimer(this, () -> {
                VoteConfig c = config;
                if (c == null || !c.menu.autoRefresh) {
                    return;
                }
                String now = voteSnapshot();
                if (!now.equals(lastVoteSnapshot)) {
                    lastVoteSnapshot = now;
                    refreshMenus();
                }
            }, period, period);
        }

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new VotePlaceholders(this).register();
            getLogger().info("已注册 PlaceholderAPI 扩展：%minigamevote_votes_<id>% 等");
        } else {
            getLogger().info("未检测到 PlaceholderAPI：菜单里的票数占位符会显示为空（投票功能不受影响）");
        }

        int noUuid = 0;
        Map<String, Integer> seen = new java.util.LinkedHashMap<>();
        for (VoteConfig.Game g : config.games.values()) {
            if (g.uuid.isEmpty()) {
                noUuid++;
                continue;
            }
            seen.merge(g.uuid, 1, Integer::sum);
        }
        getLogger().info("MiniGameVote v" + getDescription().getVersion() + " 已启用："
                + config.games.size() + " 个小游戏，阈值 " + config.threshold
                + "，状态查询 " + new Mcsm(config).statusUrls().size() + " 条，MCSM "
                + (config.mcsm.enabled ? "已启用" : "已关闭（仅计票）"));
        if (config.mcsm.enabled) {
            if (config.mcsm.url.isEmpty() || config.mcsm.apikey.isEmpty()) {
                getLogger().warning("MCSM 已启用但面板地址或 apikey 为空 —— 达标后会直接作废，"
                        + "请检查 [mcsm] 段和 /votegameadmin env");
            }
            if (noUuid > 0) {
                getLogger().warning("有 " + noUuid + " 个小游戏没配实例 uuid —— 投到它达标时无法开启");
            }
            for (Map.Entry<String, Integer> e : seen.entrySet()) {
                if (e.getValue() > 1) {
                    getLogger().warning("实例 uuid " + e.getKey() + " 被 " + e.getValue()
                            + " 个小游戏共用 —— 检查 games.*.uuid 是不是有没改的占位值");
                } else if (!Mcsm.looksLikeUuid(e.getKey())) {
                    getLogger().warning("实例 uuid \"" + e.getKey() + "\" 不像 MCSM 的 uuid"
                            + "（应为 32 位十六进制）—— 多半还是占位值，请到面板上抄真的");
                }
            }
        }
    }

    @Override
    public void onDisable() {
        if (http != null) {
            http.shutdown();
        }
    }

    private void bind(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("plugin.yml 里没有 " + name + " 命令，跳过绑定");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    public Path configFile() {
        return getDataFolder().toPath().resolve("config.toml");
    }

    /** 重载配置；⚠ 会清空当前票数 */
    public void reload() {
        try {
            Map<String, Object> t = Toml.parse(configFile());
            this.config = VoteConfig.load(t);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "读取 config.toml 失败，先用内置默认值顶着", e);
            if (this.config == null) {
                this.config = VoteConfig.load(Collections.emptyMap());
            }
        }
        generation.incrementAndGet();
        manager.reset();
        flow.forceUnlock();
        // reload 会把票数清空（调阈值也会走到这里）→ 正开着菜单的人得跟着变，
        // 否则菜单上还挂着 reload 之前的旧票数。
        refreshMenus();

        if (!config.envMissing.isEmpty()) {
            getLogger().warning("config.toml 引用了 " + config.envMissing.size() + " 个没设置的环境变量："
                    + String.join("、", config.envMissing)
                    + " —— 这些位置已按空字符串处理，请在开服脚本里 export 后再 reload");
        }
    }

    /** 把阈值写回 config.toml（只改顶层那行 threshold = ...），然后重载 */
    public boolean setThreshold(int value) {
        try {
            Path file = configFile();
            if (!Files.exists(file)) {
                saveResource("config.toml", false);
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("#") || !trimmed.matches("^threshold\\s*=.*$")) {
                    continue;
                }
                lines.set(i, "threshold = " + value);
                replaced = true;
                break;
            }
            if (!replaced) {
                lines.add(0, "threshold = " + value);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            reload();
            return true;
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "写回 threshold 失败", e);
            return false;
        }
    }

    public VoteConfig config() {
        return config;
    }

    public VoteManager votes() {
        return manager;
    }

    public HttpService http() {
        return http;
    }

    public VoteFlow flow() {
        return flow;
    }

    /** TrMenu 接入（软依赖，可能为 null 或不可用） */
    public TrMenuHook trMenu() {
        return trMenu;
    }

    /** 打开投票菜单：由控制台执行配置里的 menu.open-command */
    public void openMenu(Player player) {
        VoteConfig c = config;
        if (!c.menu.enabled || c.menu.openCommand == null || c.menu.openCommand.trim().isEmpty()) {
            Msg.send(player, c.msg("prefix") + c.msg("usage"));
            return;
        }
        String command = c.menu.openCommand.replace("%player%", player.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /**
     * 票数变了之后刷新投票菜单，让所有正开着菜单的人立刻看到新票数。
     *
     * <p>可以在任意线程调用 —— 这里会切回主线程再刷（TrMenu 那边要改玩家的背包视图）。
     *
     * <p>没装 TrMenu、配置关了、或没人开着菜单时，这里什么也不做。
     */
    public void refreshMenus() {
        VoteConfig c = config;
        if (c == null || !c.menu.autoRefresh) {
            return;
        }
        String menuId = c.menu.menuId;
        Runnable task = () -> {
            VoteConfig cfg = config;
            if (cfg == null || trMenu == null || !cfg.menu.autoRefresh) {
                return;
            }
            if (trMenu.available()) {
                trMenu.refresh(cfg.menu.menuId);
                return;
            }
            // 接不上时的最后手段：对"界面标题里含关键词"的玩家重开一次菜单（会闪，但数字是准的）
            String keyword = cfg.menu.fallbackReopenTitle;
            if (keyword != null && !keyword.isEmpty()) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        String title = p.getOpenInventory().getTitle();
                        if (title != null && title.contains(keyword)) {
                            openMenu(p);
                        }
                    } catch (Throwable ignored) {
                        // 个别玩家界面状态异常就算了，不影响别人
                    }
                }
            }
            // 刷新没接上（没装 TrMenu / 版本对不上 / 菜单 id 不对）—— 限流提示，别每次投票都刷一行
            long now = System.currentTimeMillis();
            if (now - lastRefreshWarnAt > 5 * 60 * 1000L) {
                lastRefreshWarnAt = now;
                String err = trMenu.lastError();
                getLogger().warning("菜单主动刷新不可用，菜单上的票数要玩家点了才会变"
                        + (err == null ? "" : "（" + err + "）")
                        + "。检查：/votegameadmin trmenu；兜底做法：菜单图标加 update: 40");
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    /**
     * 投票后的播报。{@code progress-audience = "voters"} 时只发给已经投过票的人：
     * 投票者本人已经收到 vote-success / vote-changed 了，这里就不再重复发给他。
     *
     * @param changed 这次是改票（文案用 progress-changed，玩家看得出票是挪过来的）
     *
     * <p>⚠ 收件人名单取自「当前在线且投过票」——玩家退出后票就没了，自然也收不到后续播报。
     */
    public void announceVote(Player voter, String gameId, int votes, boolean changed) {
        VoteConfig c = config;
        if (c.audience == VoteConfig.Audience.NONE) {
            return;
        }
        String text = c.msg("prefix") + Msg.fill(c.msg(changed ? "progress-changed" : "progress"),
                "player", voter.getName(),
                "game", c.plainName(gameId),
                "votes", String.valueOf(votes),
                "threshold", String.valueOf(c.threshold));

        if (c.audience == VoteConfig.Audience.ALL) {
            Msg.broadcast(text);
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(voter.getUniqueId())) {
                continue;
            }
            if (manager.votedGame(p.getUniqueId()) != null) {
                Msg.send(p, text);
            }
        }
    }

    public void sendStats(CommandSender target) {
        VoteConfig c = config;
        Msg.send(target, c.msg("stats-header"));
        boolean any = false;
        int rank = 0;   // 名次，从 1 开始；排序已经降序，这里只是顺着数
        for (Map.Entry<String, Integer> e : manager.ranking(c.games.keySet()).entrySet()) {
            int v = e.getValue();
            rank++;
            if (v > 0) {
                any = true;
            }
            Msg.send(target, Msg.fill(c.msg("stats-line"),
                    "rank", String.valueOf(rank),
                    "game", c.plainName(e.getKey()),
                    "votes", String.valueOf(v),
                    "threshold", String.valueOf(c.threshold)));
        }
        if (!any) {
            Msg.send(target, c.msg("prefix") + c.msg("stats-none"));
        }
        Msg.send(target, Msg.fill(c.msg("stats-total"), "total", String.valueOf(manager.totalVoters())));
    }

    /** 播音效；配置里填错名字就静音，不影响投票 */
    public static void play(Player player, String soundName, float pitch) {
        if (player == null || soundName == null || soundName.trim().isEmpty()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName.trim().toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, 1.0f, pitch);
        } catch (IllegalArgumentException ignored) {
            // 枚举名不对就不放声
        }
    }
}
