package cn.shijiu.minigamevote;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家离开当前子服 → 他投的票作废。
 * 掉线、退出、被 Velocity 转到别的子服都会触发 PlayerQuitEvent。
 */
public final class VoteListener implements Listener {

    private final MiniGameVote plugin;

    public VoteListener(MiniGameVote plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.votes().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        plugin.votes().remove(event.getPlayer().getUniqueId());
    }
}
