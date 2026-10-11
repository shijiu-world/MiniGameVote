package cn.shijiu.minigamevote;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * PlaceholderAPI 扩展（软依赖：服里没装 PAPI 就不注册，插件照常工作）。
 *
 *   %minigamevote_votes_<id>%   某个小游戏当前票数
 *   %minigamevote_threshold%    阈值
 *   %minigamevote_total%        已投票人数
 *   %minigamevote_leading%      当前票数最高的小游戏
 *   %minigamevote_mine%         自己投了谁（没投显示"未投票"）
 *   %minigamevote_mine_id%      自己投的 id（没投为空）
 */
public final class VotePlaceholders extends PlaceholderExpansion {

    private final MiniGameVote plugin;

    public VotePlaceholders(MiniGameVote plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "minigamevote";
    }

    @Override
    public String getAuthor() {
        return "拾玖世界";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        VoteConfig c = plugin.config();
        if (c == null) {
            return "";
        }
        String p = params == null ? "" : params.toLowerCase(Locale.ROOT);

        switch (p) {
            case "threshold":
                return String.valueOf(c.threshold);
            case "total":
                return String.valueOf(plugin.votes().totalVoters());
            case "leading": {
                String id = plugin.votes().leader(c.games.keySet());
                return id == null ? "无" : c.plainName(id);
            }
            case "mine": {
                String id = mineId(player);
                return id == null ? "未投票" : c.plainName(id);
            }
            case "mine_id": {
                String id = mineId(player);
                return id == null ? "" : id;
            }
            default:
                break;
        }

        if (p.startsWith("votes_")) {
            String id = p.substring("votes_".length());
            return String.valueOf(plugin.votes().votesOf(id));
        }
        return "";
    }

    private String mineId(Player player) {
        if (player == null) {
            return null;
        }
        return plugin.votes().votedGame(player.getUniqueId());
    }
}
