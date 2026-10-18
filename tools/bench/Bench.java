import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 单接口 HTTP 压测器（只依赖 JDK，无第三方 jar）。
 *
 * 用法：
 *   java Bench.java --url http://localhost:8081/api/answer/count/861 \
 *        [--method GET|POST] [--token <jwt>] [--body '{"a":1}'] \
 *        [--header "K: V"] [--concurrency 32] [--requests 5000] [--warmup 200] [--label 说明]
 */
public class Bench {

    static String url;
    static String method = "GET";
    static String token;
    static String body;
    static String label = "";
    static final Map<String, String> headers = new LinkedHashMap<>();
    static int concurrency = 32;
    static int requests = 2000;
    static int warmup = 200;
    static long timeoutSec = 30;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length - 1; i++) {
            String k = args[i], v = args[i + 1];
            switch (k) {
                case "--url" -> url = v;
                case "--method" -> method = v.toUpperCase();
                case "--token" -> token = v;
                case "--body" -> body = v;
                case "--label" -> label = v;
                case "--concurrency" -> concurrency = Integer.parseInt(v);
                case "--requests" -> requests = Integer.parseInt(v);
                case "--warmup" -> warmup = Integer.parseInt(v);
                case "--timeout" -> timeoutSec = Long.parseLong(v);
                case "--header" -> {
                    int c = v.indexOf(':');
                    headers.put(v.substring(0, c).trim(), v.substring(c + 1).trim());
                }
                default -> { }
            }
        }
        if (url == null) {
            System.out.println("缺少 --url");
            System.exit(2);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newFixedThreadPool(Math.max(2, concurrency)))
                .build();

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec));
        if (token != null) rb.header("Authorization", "Bearer " + token);
        headers.forEach(rb::header);
        if ("GET".equals(method)) {
            rb.GET();
        } else {
            rb.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
        }
        HttpRequest request = rb.build();

        // 预热（不计入统计）
        ExecutorService warmPool = Executors.newFixedThreadPool(Math.max(2, concurrency));
        List<Future<?>> warmFutures = new ArrayList<>();
        for (int i = 0; i < warmup; i++) {
            warmFutures.add(warmPool.submit(() -> {
                try { client.send(request, HttpResponse.BodyHandlers.discarding()); } catch (Exception ignored) { }
            }));
        }
        for (Future<?> f : warmFutures) f.get();
        warmPool.shutdown();

        Map<Integer, AtomicLong> status = new ConcurrentHashMap<>();
        AtomicLong errors = new AtomicLong();
        AtomicReference<String> firstError = new AtomicReference<>();

        int[] perThread = new int[concurrency];
        Arrays.fill(perThread, requests / concurrency);
        for (int i = 0; i < requests % concurrency; i++) perThread[i]++;

        long[][] latencies = new long[concurrency][];
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrency);

        for (int t = 0; t < concurrency; t++) {
            final int idx = t;
            final int count = perThread[t];
            new Thread(() -> {
                long[] lat = new long[count];
                try {
                    startGate.await();
                    for (int i = 0; i < count; i++) {
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<Void> res = client.send(request, HttpResponse.BodyHandlers.discarding());
                            status.computeIfAbsent(res.statusCode(), k -> new AtomicLong()).incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            firstError.compareAndSet(null, e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                        lat[i] = System.nanoTime() - t0;
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    latencies[idx] = lat;
                    doneGate.countDown();
                }
            }).start();
        }

        long wall0 = System.nanoTime();
        startGate.countDown();
        doneGate.await();
        long wallNs = System.nanoTime() - wall0;

        long[] all = new long[requests];
        int p = 0;
        for (long[] arr : latencies) {
            if (arr == null) continue;
            System.arraycopy(arr, 0, all, p, arr.length);
            p += arr.length;
        }
        Arrays.sort(all);

        int ok = 0;
        for (Map.Entry<Integer, AtomicLong> e : status.entrySet()) {
            if (e.getKey() >= 200 && e.getKey() < 300) ok += e.getValue().intValue();
        }

        System.out.println("=== " + (label.isEmpty() ? url : label) + " ===");
        System.out.printf("URL %s %s | 并发 %d | 请求 %d | 预热 %d%n", method, url, concurrency, requests, warmup);
        System.out.printf("QPS %.1f | 总耗时 %.2f s | 成功 %d (%.2f%%) | 传输异常 %d%n",
                requests / (wallNs / 1e9), wallNs / 1e9, ok, 100.0 * ok / requests, errors.get());
        System.out.printf("延迟 ms: avg %.2f | P50 %.2f | P90 %.2f | P95 %.2f | P99 %.2f | max %.2f%n",
                mean(all), pct(all, 50), pct(all, 90), pct(all, 95), pct(all, 99), all.length == 0 ? 0 : all[all.length - 1] / 1e6);
        StringBuilder sb = new StringBuilder("状态码分布: ");
        new TreeMap<>(status).forEach((k, v) -> sb.append(k).append('=').append(v.get()).append("  "));
        System.out.println(sb);
        if (firstError.get() != null) System.out.println("首个异常: " + firstError.get());
        System.exit(0);   // HttpClient 的 executor 是非守护线程，不显式退出会挂住
    }

    static double mean(long[] sortedNs) {
        if (sortedNs.length == 0) return 0;
        double sum = 0;
        for (long v : sortedNs) sum += v;
        return sum / sortedNs.length / 1e6;
    }

    static double pct(long[] sortedNs, int p) {
        if (sortedNs.length == 0) return 0;
        int i = (int) Math.ceil(p / 100.0 * sortedNs.length) - 1;
        return sortedNs[Math.max(0, Math.min(i, sortedNs.length - 1))] / 1e6;
    }
}
