package cn.shijiu.minigamevote;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 达标后的处理流程（全程异步，主线程零阻塞）：
 *
 *   ① 并发查询所有小游戏实例的 MCSM 状态码 —— 只要有任何一个在跑 → 本轮投票作废 + 重置
 *   ② 全都停着 → 调面板接口启动那个实例 → 重置票数
 *   ③ 处理期间又有游戏达标 → 记 pending，本轮跑完立刻再走一遍
 */
public final class VoteFlow {

    private final MiniGameVote plugin;
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public VoteFlow(MiniGameVote plugin) {
        this.plugin = plugin;
    }

    /** 主线程调用：投完一票后看是否达标 */
    public void onVote(String gameId) {
        VoteConfig c = plugin.config();
        if (c == null || gameId == null) {
            return;
        }
        if (plugin.votes().votesOf(gameId) < c.threshold) {
            return;
        }
        if (!resolving.compareAndSet(false, true)) {
            pending.set(true);
            return;
        }
        run(gameId);
    }

    private void run(String gameId) {
        VoteConfig c = plugin.config();
        final int gen = plugin.generation();
        Mcsm mcsm = new Mcsm(c);
        List<String> urls = mcsm.statusUrls();

        // MCSM 关掉、或一个小游戏都没配 uuid → 没法查也没法开，只重置
        if (!c.mcsm.enabled || urls.isEmpty()) {
            open(gameId, gen);
            return;
        }

        Map<String, String> headers = mcsm.authHeaders();
        List<CompletableFuture<HttpService.Result>> futures = new ArrayList<>();
        for (String url : urls) {
            futures.add(plugin.http().request(url, "GET", null, c.http.timeoutMs, c.http.userAgent, headers));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
            boolean busy = false;
            String detail = "";
            try {
                for (int i = 0; i < futures.size() && !busy; i++) {
                    HttpService.Result r = futures.get(i).join();
                    if (!r.ok) {
                        // 查不到：按 on-query-error 决定算空闲还是算占用
                        if (c.mcsm.errorMeansBusy) {
                            busy = true;
                            detail = urls.get(i) + " → 查询失败（HTTP " + r.code + "）";
                        }
                        continue;
                    }
                    Map<String, Integer> statuses = mcsm.parseStatuses(r.body);
                    for (Map.Entry<String, Integer> e : statuses.entrySet()) {
                        if (mcsm.isBusy(e.getKey(), e.getValue())) {
                            busy = true;
                            detail = mcsm.displayName(e.getKey()) + " " + Mcsm.statusText(e.getValue());
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                // ⚠ 这段在异步线程里，一旦抛出去就会被 CompletableFuture 悄悄吞掉，
                // finish() 就永远不执行 → resolving 卡在 true → 之后达标全都石沉大海，
                // 而且后台一条日志都没有。所以必须接住，并且无论如何都要收尾。
                plugin.getLogger().log(Level.WARNING, "查询 MCSM 实例状态时出错，按「空闲」继续", t);
            }

            if (busy) {
                final String d = detail;
                sync(() -> {
                    try {
                        if (!stale(gen)) {
                            announceBusy(d);
                            plugin.votes().reset();
                            plugin.refreshMenus();
                        }
                    } finally {
                        finish();
                    }
                });
            } else {
                open(gameId, gen);
            }
        });
    }

    /** 配置在这一轮跑的过程中被 reload 过 → 本轮结果作废，别动新票数 */
    private boolean stale(int gen) {
        return plugin.generation() != gen;
    }

    /** 调面板接口启动该小游戏对应的实例 */
    private void open(String gameId, int gen) {
        VoteConfig c = plugin.config();
        Mcsm mcsm = new Mcsm(c);
        VoteConfig.Game g = c.game(gameId);

        if (!c.mcsm.enabled || g == null || g.uuid.isEmpty()) {
            sync(() -> {
                try {
                    if (!stale(gen)) {
                        noUuid(gameId);
                        plugin.votes().reset();
                        plugin.refreshMenus();
                    }
                } finally {
                    finish();
                }
            });
            return;
        }

        String url = mcsm.openUrl(g);
        plugin.http().request(url, "GET", null, c.http.timeoutMs, c.http.userAgent, mcsm.authHeaders())
                .whenComplete((r, throwable) -> sync(() -> {
                    try {
                        if (!stale(gen)) {
                            String code = r == null ? "-1" : String.valueOf(r.code);
                            if (r != null && r.success2xx()) {
                                broadcast(c, "threshold-reached",
                                        "game", c.plainName(gameId),
                                        "threshold", String.valueOf(c.threshold),
                                        "code", code);
                            } else {
                                broadcast(c, "open-failed",
                                        "game", c.plainName(gameId),
                                        "threshold", String.valueOf(c.threshold),
                                        "code", code);
                            }
                            plugin.votes().reset();
                            plugin.refreshMenus();
                        }
                    } finally {
                        // 无论中途出什么事都要解锁，否则 resolving 卡死、后续达标全不触发
                        finish();
                    }
                }));
    }

    private void announceBusy(String detail) {
        VoteConfig c = plugin.config();
        broadcast(c, "busy", "detail", detail);
    }

    private void noUuid(String gameId) {
        VoteConfig c = plugin.config();
        Msg.broadcast(c.msg("prefix") + Msg.fill(c.msg("no-uuid"),
                "game", c.plainName(gameId),
                "threshold", String.valueOf(c.threshold)));
    }

    private void finish() {
        resolving.set(false);
        if (pending.compareAndSet(true, false)) {
            VoteConfig c = plugin.config();
            String next = plugin.votes().firstReached(c.games.keySet(), c.threshold);
            if (next != null && resolving.compareAndSet(false, true)) {
                run(next);
            }
        }
    }

    /** 管理员手动重置时调用：把可能卡住的状态解锁 */
    public void forceUnlock() {
        resolving.set(false);
        pending.set(false);
    }

    public boolean isResolving() {
        return resolving.get();
    }

    private void broadcast(VoteConfig c, String key, String... kv) {
        Msg.broadcast(c.msg("prefix") + Msg.fill(c.msg(key), kv));
    }

    private void sync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
