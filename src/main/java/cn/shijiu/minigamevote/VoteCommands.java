package cn.shijiu.minigamevote;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /votegame —— 不带参数开菜单，带 id 直接投票，stats 看票数 */
public final class VoteCommands implements TabExecutor {

    private static final String PERM_VOTE = "minigamevote.vote";

    private final MiniGameVote plugin;

    public VoteCommands(MiniGameVote plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        VoteConfig c = plugin.config();

        if (!(sender instanceof Player player)) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("stats")) {
                plugin.sendStats(sender);
            } else {
                Msg.send(sender, c.msg("prefix") + "&7控制台只能看票数：&a/votegame stats");
            }
            return true;
        }

        if (!player.hasPermission(PERM_VOTE)) {
            Msg.send(player, c.msg("prefix") + c.msg("no-permission"));
            MiniGameVote.play(player, c.soundDenied, 1.0f);
            return true;
        }

        if (args.length == 0) {
            plugin.openMenu(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            plugin.sendStats(player);
            return true;
        }

        cast(player, args[0].toLowerCase(Locale.ROOT));
        return true;
    }

    private void cast(Player player, String id) {
        VoteConfig c = plugin.config();
        VoteConfig.Game game = c.game(id);
        if (game == null) {
            Msg.send(player, c.msg("prefix") + Msg.fill(c.msg("unknown-game"), "id", id));
            MiniGameVote.play(player, c.soundDenied, 1.0f);
            return;
        }

        VoteManager.Cast result = plugin.votes().cast(player.getUniqueId(), id);
        if (!result.success) {
            // 投的还是同一个：不算数，也不重复播报
            Msg.send(player, c.msg("prefix") + Msg.fill(c.msg("already-voted"), "game", c.plainName(result.gameId)));
            MiniGameVote.play(player, c.soundDenied, 1.0f);
            return;
        }

        int votes = plugin.votes().votesOf(id);
        if (result.changed()) {
            // 改票：上一票已经扣掉了，提示里带上原来那个
            Msg.send(player, c.msg("prefix") + Msg.fill(c.msg("vote-changed"),
                    "game", game.name,
                    "old", c.plainName(result.previous),
                    "votes", String.valueOf(votes),
                    "threshold", String.valueOf(c.threshold)));
        } else {
            Msg.send(player, c.msg("prefix") + Msg.fill(c.msg("vote-success"),
                    "game", game.name,
                    "votes", String.valueOf(votes),
                    "threshold", String.valueOf(c.threshold)));
        }
        MiniGameVote.play(player, c.soundVote, 2.0f);

        plugin.announceVote(player, id, votes, result.changed());
        // 让所有正开着菜单的人（不只投票者自己）看到新票数
        plugin.refreshMenus();

        if (c.menu.enabled && c.menu.reopenAfterVote) {
            plugin.openMenu(player);
        }

        plugin.flow().onVote(id);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>(plugin.config().games.keySet());
        options.add("stats");
        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        Collections.sort(options);
        return options;
    }
}
