package cn.shijiu.minigamevote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步 HTTP：所有请求都跑在独立线程池上，绝不占主线程。
 * 用 JDK 自带的 java.net.http.HttpClient（Java 11+），不引第三方依赖。
 */
public final class HttpService {

    public static final class Result {
        public final boolean ok;
        public final int code;
        public final String body;

        Result(boolean ok, int code, String body) {
            this.ok = ok;
            this.code = code;
            this.body = body == null ? "" : body;
        }

        public boolean success2xx() {
            return ok && code >= 200 && code < 300;
        }
    }

    private final ExecutorService pool;
    private final HttpClient client;

    public HttpService() {
        AtomicInteger n = new AtomicInteger(0);
        // 池子只用来"占一个线程、阻塞等 send() 返回"。
        // 数量要 ≥ 一次达标发的并发请求数（6 个小游戏 + 1 次开启），否则请求得排队。
        this.pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "MiniGameVote-HTTP-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // ⚠ 千万别给 HttpClient 设 .executor(pool)。
        // HttpClient 的 executor 是给它内部跑连接/读写用的，而我们的任务正阻塞在
        // send() 上等结果 —— 两边抢同一个固定池子会互相饿死：
        // 8 个线程全卡在 send()，内部任务没线程跑，请求只能等到超时才算完，
        // 表现就是"查状态全部失败"，占用检测形同虚设。
        // 不设 executor = 用 HttpClient 自己的默认线程池，两边互不干扰。
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 发起请求；返回的 Future 在线程池上完成，调用方自行 thenAccept 回主线程 */
    public CompletableFuture<Result> request(String url, String method, String body, long timeoutMs,
                                             String userAgent, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> doRequest(url, method, body, timeoutMs, userAgent, headers), pool);
    }

    /**
     * 在同一个池子上跑一个阻塞任务（查子服人数用）。
     *
     * <p>ping 是同步 socket，必须放异步线程；复用这个池子省得再开一个。
     * ⚠ 别拿它发 HTTP 请求 —— 那是 {@link #request} 的活，两个都占线程池会互相饿死。
     */
    public <T> CompletableFuture<T> async(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, pool);
    }

    private Result doRequest(String url, String method, String body, long timeoutMs, String userAgent,
                             Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent == null ? "MiniGameVote" : userAgent)
                    .header("Accept", "*/*");

            // 自定义头（Authorization 之类）放在默认头之后，允许覆盖
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getKey().isEmpty()) {
                        b.header(e.getKey(), e.getValue());
                    }
                }
            }

            boolean hasBody = body != null && !body.isEmpty();
            String m = method == null ? "GET" : method.trim().toUpperCase();
            boolean contentTypeSet = headers != null && containsKeyIgnoreCase(headers, "Content-Type");
            if (hasBody) {
                if (!contentTypeSet) {
                    b.header("Content-Type", "application/json");
                }
                b.POST(HttpRequest.BodyPublishers.ofString(body));
            } else if ("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m)) {
                if (!contentTypeSet) {
                    b.header("Content-Type", "application/json");
                }
                b.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                b.GET();
            }

            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(true, resp.statusCode(), resp.body());
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName();
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                msg += ": " + e.getMessage();
            }
            return new Result(false, -1, msg);
        }
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> map, String key) {
        for (String k : map.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
