package cn.shijiu.minigamevote;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 达标后的处理流程（全程异步，主线程零阻塞）。
 *
 * <pre>
 *   ① 查所有小游戏实例的状态（MCSM）
 *   ② 投的那个<b>自己就开着</b> → 忽视：播报 + 重置票数，结束
 *   ③ 状态查不到 → 按 on-query-error：busy=作废本轮 / idle=当作空闲继续开
 *   ④ 别的游戏开着：
 *        - 里面还有人（ping 子服人数）→ 播报 + 作废本轮，不关
 *        - 人数查不到（没配 address / ping 不通）→ 播报 + 作废本轮，<b>不敢擅自关</b>
 *        - 都没人 → 发关闭请求 → 轮询等它真关掉（超时按已关闭处理）→ 再开启目标游戏
 *   ⑤ 没有别的游戏在跑 → 直接开启
 * </pre>
 *
 * <p>🟢 一句话原则：**凡是"不确定"的地方一律往「不开、不关」那一侧倒** ——
 * 多等一轮投票没什么，把还有人在玩的小游戏关掉是事故。
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

    // ------------------------------------------------------------------
    // ① 查状态
    // ------------------------------------------------------------------

    private void run(String gameId) {
        VoteConfig c = plugin.config();
        final int gen = plugin.generation();
        Mcsm mcsm = new Mcsm(c);

        // 目标游戏自己没配 uuid → 没什么可查的，直接走"开不了"那条路
        VoteConfig.Game self = c.game(gameId);
        if (self == null || self.uuid.isEmpty()) {
            open(gameId, gen);
            return;
        }

        List<String> urls = mcsm.statusUrls();
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
            Map<String, Integer> statuses = new LinkedHashMap<>();
            String failReason = null;
            try {
                for (int i = 0; i < futures.size(); i++) {
                    HttpService.Result r = futures.get(i).join();
                    String where = Mcsm.mask(urls.get(i), c.mcsm.apikey);
                    if (!r.ok) {
                        failReason = "HTTP " + r.code + "（" + where + "）";
                        continue;
                    }
                    // ⚠ 查不到 ≠ 没在跑：面板回 200 但 data 为 null（uuid 不对/没权限）也走这里
                    Mcsm.Probe probe = mcsm.probe(r.body);
                    if (!probe.valid) {
                        failReason = probe.reason + "（" + where + "）";
                        continue;
                    }
                    statuses.putAll(probe.statuses);
                }
            } catch (Throwable t) {
                // ⚠ 这段在异步线程里，抛出去会被 CompletableFuture 悄悄吞掉，
                // finish() 就永远不执行 → resolving 卡在 true → 之后达标全都石沉大海
                plugin.getLogger().log(Level.WARNING, "查询 MCSM 实例状态时出错", t);
                failReason = "查询出错：" + t;
            }
            decide(gameId, gen, statuses, failReason);
        });
    }

    // ------------------------------------------------------------------
    // ② 判定
    // ------------------------------------------------------------------

    private void decide(String gameId, int gen, Map<String, Integer> statuses, String failReason) {
        VoteConfig c = plugin.config();
        Mcsm mcsm = new Mcsm(c);
        VoteConfig.Game self = c.game(gameId);

        // ① 自己就开着 → 忽视
        if (self != null) {
            Integer st = statuses.get(self.uuid);
            if (st != null && mcsm.isBusy(self.uuid, st)) {
                voidRound(gen, "self-running", "game", c.plainName(gameId));
                return;
            }
        }

        // ② 状态不明 → 按 on-query-error
        if (failReason != null) {
            plugin.getLogger().warning("有实例状态没查到：" + failReason);
            if (c.mcsm.errorMeansBusy) {
                voidRound(gen, "busy", "detail", "状态查询失败（" + failReason + "）");
            } else {
                open(gameId, gen);
            }
            return;
        }

        // ③ 别的实例在跑？分成「别的小游戏」和「外部 watch 实例」
        List<VoteConfig.Game> others = new ArrayList<>();
        List<String> external = new ArrayList<>();
        for (Map.Entry<String, Integer> e : statuses.entrySet()) {
            if (!mcsm.isBusy(e.getKey(), e.getValue())) {
                continue;
            }
            if (self != null && e.getKey().equals(self.uuid)) {
                continue;   // 自己，已经在 ① 处理过了
            }
            VoteConfig.Game g = gameByUuid(c, e.getKey());
            if (g != null) {
                others.add(g);
            } else {
                external.add(e.getKey());
            }
        }
        if (!external.isEmpty()) {
            voidRound(gen, "busy", "detail", "额外监视的实例 " + external.get(0) + " 正在运行");
            return;
        }
        if (others.isEmpty()) {
            open(gameId, gen);
            return;
        }

        // ④ 那些还在跑的里面有没有人
        checkPlayersThenClose(gameId, gen, others);
    }

    /** 按 uuid 找小游戏；找不到（是 watch 里的外部实例）返回 null */
    private static VoteConfig.Game gameByUuid(VoteConfig c, String uuid) {
        for (VoteConfig.Game g : c.games.values()) {
            if (uuid.equals(g.uuid)) {
                return g;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // ④ 查人数 → 关 → 等 → 开
    // ------------------------------------------------------------------

    private static final class People {
        final VoteConfig.Game game;
        /** null = 查不到（没配 address 或 ping 不通） */
        final Integer count;

        People(VoteConfig.Game game, Integer count) {
            this.game = game;
            this.count = count;
        }
    }

    private void checkPlayersThenClose(String gameId, int gen, List<VoteConfig.Game> others) {
        VoteConfig c = plugin.config();
        List<CompletableFuture<People>> pings = new ArrayList<>();
        for (VoteConfig.Game g : others) {
            pings.add(plugin.http().async(() ->
                    new People(g, ServerPing.online(g.address, c.mcsm.pingTimeoutMs))));
        }

        CompletableFuture.allOf(pings.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
            try {
                VoteConfig.Game occupied = null;
                int players = 0;
                VoteConfig.Game unknown = null;
                for (CompletableFuture<People> f : pings) {
                    People p = f.join();
                    if (p.count == null) {
                        if (unknown == null) {
                            unknown = p.game;
                        }
                    } else if (p.count > 0 && occupied == null) {
                        occupied = p.game;
                        players = p.count;
                    }
                }
                // 🟢 凡是"不确定"就往不关那一侧倒：查不到人数当作有人
                if (unknown != null) {
                    voidRound(gen, "other-unknown", "other", c.plainName(unknown.id));
                    return;
                }
                if (occupied != null) {
                    voidRound(gen, "other-occupied",
                            "other", c.plainName(occupied.id),
                            "players", String.valueOf(players));
                    return;
                }
                closeThenOpen(gameId, gen, others);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "查子服人数时出错，本轮作废", t);
                voidRound(gen, "busy", "detail", "人数查询出错");
            }
        });
    }

    private void closeThenOpen(String gameId, int gen, List<VoteConfig.Game> others) {
        VoteConfig c = plugin.config();
        Mcsm mcsm = new Mcsm(c);
        final String names = names(others);

        sync(() -> {
            if (!stale(gen)) {
                broadcast(c, "closing-others",
                        "game", c.plainName(gameId),
                        "threshold", String.valueOf(c.threshold),
                        "other", names);
            }
        });

        for (VoteConfig.Game g : others) {
            final VoteConfig.Game target = g;
            String url = mcsm.stopUrl(g);
            plugin.http().request(url, "GET", null, c.http.timeoutMs, c.http.userAgent, mcsm.authHeaders())
                    .whenComplete((r, throwable) -> {
                        if (r == null || !r.success2xx()) {
                            String code = r == null ? "-1" : String.valueOf(r.code);
                            plugin.getLogger().warning("关闭实例失败："
                                    + Mcsm.mask(url, c.mcsm.apikey) + " → HTTP " + code);
                            sync(() -> {
                                if (!stale(gen)) {
                                    broadcast(c, "stop-failed",
                                            "other", c.plainName(target.id),
                                            "code", code);
                                }
                            });
                        }
                    });
        }

        // 轮询等它们真关掉（发完请求不代表已经停了）
        waitStopped(gameId, gen, others, System.nanoTime());
    }

    private void waitStopped(String gameId, int gen, List<VoteConfig.Game> others, long startNano) {
        VoteConfig c = plugin.config();
        Mcsm mcsm = new Mcsm(c);
        final String names = names(others);

        List<CompletableFuture<HttpService.Result>> futures = new ArrayList<>();
        for (VoteConfig.Game g : others) {
            futures.add(plugin.http().request(mcsm.statusUrl(g), "GET", null,
                    c.http.timeoutMs, c.http.userAgent, mcsm.authHeaders()));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
            boolean allStopped = true;
            boolean sawAny = false;
            try {
                for (CompletableFuture<HttpService.Result> f : futures) {
                    HttpService.Result r = f.join();
                    Mcsm.Probe p = mcsm.probe(r == null ? "" : r.body);
                    if (!p.valid) {
                        allStopped = false;      // 查不到就当还没关完，继续等（超时兜底）
                        continue;
                    }
                    for (Integer st : p.statuses.values()) {
                        sawAny = true;
                        if (!Mcsm.isStopped(st)) {
                            allStopped = false;
                        }
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "等待实例关闭时出错，按还没关完继续等", t);
                allStopped = false;
            }

            if (allStopped && sawAny) {
                sync(() -> {
                    if (!stale(gen)) {
                        broadcast(c, "others-closed", "other", names, "game", c.plainName(gameId));
                    }
                });
                open(gameId, gen);
                return;
            }

            // 用单调时钟算耗时：期间系统时间被改过也不会算错
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
            if (elapsedMs >= c.mcsm.stopTimeoutMs) {
                plugin.getLogger().warning("等待 " + names + " 关闭超时（" + elapsedMs + " ms），按已关闭处理");
                sync(() -> {
                    if (!stale(gen)) {
                        broadcast(c, "close-timeout",
                                "other", names,
                                "game", c.plainName(gameId),
                                "seconds", String.valueOf(c.mcsm.stopTimeoutMs / 1000));
                    }
                });
                open(gameId, gen);
                return;
            }

            long ticks = Math.max(1L, c.mcsm.stopPollMs / 50L);
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                if (stale(gen)) {
                    return;     // 期间 reload 过，这一轮作废（reload 里会 forceUnlock）
                }
                waitStopped(gameId, gen, others, startNano);
            }, ticks);
        });
    }

    private String names(List<VoteConfig.Game> games) {
        VoteConfig c = plugin.config();
        StringBuilder sb = new StringBuilder();
        for (VoteConfig.Game g : games) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(c.plainName(g.id));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 收尾：作废本轮 / 开启 / 播报
    // ------------------------------------------------------------------

    /** 本轮作废：播报 + 清空票数 + 刷新菜单 + 解锁 */
    private void voidRound(int gen, String messageKey, String... kv) {
        sync(() -> {
            try {
                if (!stale(gen)) {
                    broadcast(plugin.config(), messageKey, kv);
                    plugin.votes().reset();
                    plugin.refreshMenus();
                }
            } finally {
                finish();
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
