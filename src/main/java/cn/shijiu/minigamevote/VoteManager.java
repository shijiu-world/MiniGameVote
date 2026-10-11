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
 * 一人一票，可以改票：voted 记「谁投了谁」，votes 记「每个小游戏几票」。
 * 已经投过的再投别的 → 上一票自动取消（{@link #cast(UUID, String)} 里一减一加）。
 * 玩家退出（含切子服）→ remove() 把票扣掉，票就没了。
 */
public final class VoteManager {

    /**
     * 一次投票的结果。
     *
     * <p>三种情况：
     * <pre>
     *   success=true , previous=null  → 首次投票
     *   success=true , previous!=null → 改票：上一票（previous）已自动取消
     *   success=false                → 重复投同一个，什么都没变（gameId 就是他当前投的那个）
     * </pre>
     */
    public static final class Cast {
        public final boolean success;

        /** 本次投的（或他当前投的）小游戏 id */
        public final String gameId;

        /** 改票前投的那个 id；首次投票为 null */
        public final String previous;

        Cast(boolean success, String gameId, String previous) {
            this.success = success;
            this.gameId = gameId;
            this.previous = previous;
        }

        /** 是不是"改票"（把上一票挪到别的小游戏） */
        public boolean changed() {
            return success && previous != null && !previous.equals(gameId);
        }
    }

    private final ConcurrentHashMap<String, AtomicInteger> votes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> voted = new ConcurrentHashMap<>();

    /**
     * 投票。已经投过的再投别的 → **改票**：上一票自动取消，只保留最新那一票。
     * 投的还是同一个 → 不算数（success=false），避免刷播报。
     */
    public synchronized Cast cast(UUID uuid, String gameId) {
        String old = voted.put(uuid, gameId);
        if (old == null) {
            votes.computeIfAbsent(gameId, k -> new AtomicInteger(0)).incrementAndGet();
            return new Cast(true, gameId, null);
        }
        if (old.equals(gameId)) {
            return new Cast(false, gameId, old);
        }
        // 改票：旧的减一，新的加一。两边一减一加，投票人数不变。
        decrement(old);
        votes.computeIfAbsent(gameId, k -> new AtomicInteger(0)).incrementAndGet();
        return new Cast(true, gameId, old);
    }

    /** 玩家退出 / 被踢：他的票作废 */
    public synchronized void remove(UUID uuid) {
        String gameId = voted.remove(uuid);
        if (gameId == null) {
            return;
        }
        decrement(gameId);
    }

    /** 某个小游戏减一票；减到 0 就把计数整个移除（省得留下 0 票的僵尸条目） */
    private void decrement(String gameId) {
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

    /**
     * 排名快照：id → 票数，**按票数降序**；票数相同时保持配置里的先后顺序。
     *
     * <p>⚠ 先把票数取成快照再排序：直接在比较器里调 {@link #votesOf(String)} 的话，
     * 排序过程中票数被别的线程改了，比较结果前后不一致，TimSort 可能抛
     * "Comparison method violates its general contract!"（元素多时才容易撞上，但没必要赌）。
     */
    public Map<String, Integer> ranking(java.util.Collection<String> order) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (String id : order) {
            list.add(new java.util.AbstractMap.SimpleEntry<>(id, votesOf(id)));
        }
        // 降序；List.sort 是稳定排序，所以同票的仍按配置顺序
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : list) {
            out.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    /** 所有已投票玩家的 uuid 快照 */
    public List<UUID> voters() {
        return new ArrayList<>(voted.keySet());
    }
}
