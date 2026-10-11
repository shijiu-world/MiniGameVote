package cn.shijiu.minigamevote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 票数与投票记录。
 *
 * 一人一票：voted 记「谁投了谁」，votes 记「每个小游戏几票」。
 * 玩家退出（含切子服）→ remove() 把票扣掉，票就没了。
 */
public final class VoteManager {

    public static final class Cast {
        public final boolean success;

        /** 已投过票时，这里是之前投的那个游戏 id；成功时是本次投的 id */
        public final String gameId;

        Cast(boolean success, String gameId) {
            this.success = success;
            this.gameId = gameId;
        }
    }

    private final ConcurrentHashMap<String, AtomicInteger> votes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> voted = new ConcurrentHashMap<>();

    public synchronized Cast cast(UUID uuid, String gameId) {
        String old = voted.putIfAbsent(uuid, gameId);
        if (old != null) {
            return new Cast(false, old);
        }
        votes.computeIfAbsent(gameId, k -> new AtomicInteger(0)).incrementAndGet();
        return new Cast(true, gameId);
    }

    /** 玩家退出 / 被踢：他的票作废 */
    public synchronized void remove(UUID uuid) {
        String gameId = voted.remove(uuid);
        if (gameId == null) {
            return;
        }
        AtomicInteger counter = votes.get(gameId);
        if (counter == null) {
            return;
        }
        if (counter.decrementAndGet() <= 0) {
            votes.remove(gameId);
        }
    }

    public synchronized void reset() {
        votes.clear();
        voted.clear();
    }

    public int votesOf(String gameId) {
        AtomicInteger counter = votes.get(gameId);
        return counter == null ? 0 : counter.get();
    }

    public String votedGame(UUID uuid) {
        return voted.get(uuid);
    }

    public int totalVoters() {
        return voted.size();
    }

    /** 按配置顺序找第一个票数 >= threshold 的小游戏；没有返回 null */
    public String firstReached(java.util.Collection<String> order, int threshold) {
        for (String id : order) {
            if (votesOf(id) >= threshold) {
                return id;
            }
        }
        return null;
    }

    /** 票数最高的小游戏（并列取配置里靠前的）；没人投票返回 null */
    public String leader(java.util.Collection<String> order) {
        String best = null;
        int bestVotes = 0;
        for (String id : order) {
            int v = votesOf(id);
            if (v > bestVotes) {
                bestVotes = v;
                best = id;
            }
        }
        return best;
    }

    /** 快照：id → 票数（给 stats 展示用，按传入顺序） */
    public Map<String, Integer> snapshot(java.util.Collection<String> order) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String id : order) {
            out.put(id, votesOf(id));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 所有已投票玩家的 uuid 快照 */
    public List<UUID> voters() {
        return new ArrayList<>(voted.keySet());
    }
}
