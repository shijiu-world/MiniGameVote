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
 *   玩家 /votegame <id>   → 直接投票；一人一票
 *   玩家退出子服          → 他投的票自动作废
 *   某个小游戏票数达标    → 异步查所有实例 → 都空闲才发开启请求 → 重置票数
 */
public final class MiniGameVote extends JavaPlugin {

    private VoteConfig config;
    private VoteManager manager;
    private HttpService http;
    private VoteFlow flow;

    /**
     * 配置代次：每次 reload 加一。
     *
     * <p>用途：HTTP 是异步的，reload 之后旧流程的回调才回来，如果照常执行就会把
     * reload 之后新投的票 reset 掉。回调时比对代次，不一致就丢弃这一轮结果。
     */
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public int generation() {
        return generation.get();
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
     * 投票后的播报。{@code progress-audience = "voters"} 时只发给已经投过票的人：
     * 投票者本人已经收到 vote-success 了，这里就不再重复发给他。
     *
     * <p>⚠ 收件人名单取自「当前在线且投过票」——玩家退出后票就没了，自然也收不到后续播报。
     */
    public void announceVote(Player voter, String gameId, int votes) {
        VoteConfig c = config;
        if (c.audience == VoteConfig.Audience.NONE) {
            return;
        }
        String text = c.msg("prefix") + Msg.fill(c.msg("progress"),
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
        for (Map.Entry<String, Integer> e : manager.snapshot(c.games.keySet()).entrySet()) {
            int v = e.getValue();
            if (v > 0) {
                any = true;
            }
            Msg.send(target, Msg.fill(c.msg("stats-line"),
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
