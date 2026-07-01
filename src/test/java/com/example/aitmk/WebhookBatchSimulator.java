package com.example.aitmk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量 WhatsApp webhook 压力测试工具，支持多种并发场景。
 *
 * <h3>快速使用</h3>
 * <pre>
 * mvn -q -DskipTests test-compile exec:java \
 *   -Dexec.mainClass=com.example.aitmk.WebhookBatchSimulator \
 *   -Dexec.args="--mode flood --count 100 --threads 10"
 * </pre>
 *
 * <h3>场景模式</h3>
 * <table>
 *   <tr><th>模式</th><th>测试目标</th></tr>
 *   <tr><td>flood</td><td>新客户洪峰 — 并发分配准确性、round-robin 均衡性、DB 锁竞争</td></tr>
 *   <tr><td>hot-agent</td><td>单坐席超多会话 — 一个坐席挂 N 个会话的压力</td></tr>
 *   <tr><td>fat-conversation</td><td>单会话超多聊天记录 — 反复对同一客户发消息</td></tr>
 *   <tr><td>mixed</td><td>混合场景 — 新客户 + 老客户 + 同客户连发</td></tr>
 *   <tr><td>burst</td><td>同客户快速连发 — 幂等去重 + 消息顺序</td></tr>
 *   <tr><td>ramp</td><td>阶梯增压 — 渐进提升并发找到系统瓶颈</td></tr>
 * </table>
 *
 * <h3>参数</h3>
 * <pre>
 *   --url           webhook 地址 (默认 http://localhost:6153/webhook)
 *   --mode          场景模式 (默认 flood)
 *   --count         总消息数 (默认 100)
 *   --threads       并发线程数 (默认 10)
 *   --pool-size     号码池大小 (默认 50)
 *   --phone-prefix  号码前缀 (默认 69906210000)
 *   --interval      消息间隔 ms (默认 0, 全速)
 *   --ramp-step     阶梯步长 (默认 10)
 *   --ramp-max      阶梯最大并发 (默认 100)
 *   --fat-count     fat 模式消息数 (默认 500)
 *   --hot-count     hot-agent 模式每客户消息数 (默认 3)
 *   --db-url        DB 连接串 (用于统计查询, 可选)
 *   --db-user       DB 用户名 (可选)
 *   --db-pass       DB 密码 (可选)
 *   --business-id   模拟的 business phone_number_id (默认 1019964791197772)
 * </pre>
 */
public class WebhookBatchSimulator {

    // ── 配置 ──
    private String webhookUrl = "http://localhost:6153/webhook";
    private String mode = "flood";
    private int count = 500;
    private int threads = 10;
    private int poolSize = 50;
    private String phonePrefix = "69906210000";
    private long intervalMs = 0;
    private int rampStep = 10;
    private int rampMax = 100;
    private int fatCount = 500;
    private int hotCount = 3;
    private String dbUrl;
    private String dbUser;
    private String dbPass;
    private String businessId = "1019964791197772";

    // ── 统计 ──
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusCodes = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();

    public static void main(String[] args) throws Exception {
        new WebhookBatchSimulator().run(argsToMap(args));
    }

    private void run(Map<String, String> args) throws Exception {
        parseArgs(args);
        printBanner();

        Instant start = Instant.now();
        switch (mode) {
            case "flood"            -> runFlood();
            case "hot-agent"        -> runHotAgent();
            case "fat-conversation" -> runFatConversation();
            case "mixed"            -> runMixed();
            case "burst"            -> runBurst();
            case "ramp"             -> runRamp();
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.exit(1);
            }
        }
        Instant end = Instant.now();
        printReport(Duration.between(start, end));
    }

    // ═══════════════════════════════════════════════════
    // 场景实现
    // ═══════════════════════════════════════════════════

    /** 新客户洪峰：N 个不同新客户，并发打入 */
    private void runFlood() throws Exception {
        List<String> phones = generatePhonePool(Math.min(count, poolSize));
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String phone = phones.get(i % phones.size());
            String payload = buildPayload(phone, "wamid.flood." + i,
                    "Hello from flood test #" + i, false);
            tasks.add(new Task(phone, buildRequest(payload)));
        }
        System.out.printf("Flood mode: %d messages, %d unique phones, %d threads%n%n",
                count, phones.size(), threads);
        executeConcurrently(tasks);
    }

    /** 单坐席超多会话：每个客户发多条消息，模拟一个坐席挂大量会话 */
    private void runHotAgent() throws Exception {
        List<String> phones = generatePhonePool(Math.min(count, poolSize));
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String phone = phones.get(i % phones.size());
            for (int j = 0; j < hotCount; j++) {
                String payload = buildPayload(phone,
                        "wamid.hot." + phone + "." + j,
                        "hot-agent message " + j + " from " + phone, false);
                tasks.add(new Task(phone, buildRequest(payload)));
            }
        }
        System.out.printf("Hot-agent mode: %d phones x %d msgs = %d total, %d threads%n",
                count, hotCount, tasks.size(), threads);
        System.out.println("NOTE: Ensure only ONE whitelist agent is ONLINE before running.\n");
        executeConcurrently(tasks);
    }

    /** 单会话超多消息：反复对同一个客户发消息 */
    private void runFatConversation() throws Exception {
        String phone = generatePhonePool(1).get(0);
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < fatCount; i++) {
            String payload = buildPayload(phone,
                    "wamid.fat." + i,
                    "fat-conversation message #" + i + " " + filler(i), false);
            tasks.add(new Task(phone, buildRequest(payload)));
        }
        System.out.printf("Fat-conversation mode: %d messages to phone %s, %d threads%n%n",
                fatCount, phone, threads);
        executeConcurrently(tasks);
    }

    /** 混合场景：新客户 + 老客户回复 + 同客户连发 */
    private void runMixed() throws Exception {
        List<String> pool = generatePhonePool(poolSize);
        int newCount = count / 3;
        int returningCount = count / 3;
        int burstCount = count - newCount - returningCount;

        List<Task> tasks = new ArrayList<>();
        // 新客户
        for (int i = 0; i < newCount; i++) {
            String phone = pool.get(i);
            String payload = buildPayload(phone, "wamid.mix.new." + i,
                    "mixed new customer #" + i, false);
            tasks.add(new Task(phone, buildRequest(payload)));
        }
        // 老客户回复（复用前面新客户的手机号）
        for (int i = 0; i < returningCount; i++) {
            String phone = pool.get(i % Math.min(newCount, pool.size()));
            String payload = buildPayload(phone, "wamid.mix.ret." + i,
                    "mixed returning message #" + i, false);
            tasks.add(new Task(phone, buildRequest(payload)));
        }
        // 同客户快速连发
        String burstPhone = pool.get(0);
        for (int i = 0; i < burstCount; i++) {
            String payload = buildPayload(burstPhone, "wamid.mix.burst." + i,
                    "mixed burst message #" + i, false);
            tasks.add(new Task(burstPhone, buildRequest(payload)));
        }

        Collections.shuffle(tasks);
        System.out.printf("Mixed mode: %d new + %d returning + %d burst = %d total, %d threads%n%n",
                newCount, returningCount, burstCount, tasks.size(), threads);
        executeConcurrently(tasks);
    }

    /** 同客户快速连发：测试幂等去重 (50% 重复消息) */
    private void runBurst() throws Exception {
        String phone = generatePhonePool(1).get(0);
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 50% 使用相同 externalMessageId 测试去重
            String msgId = (i % 2 == 0) ? "wamid.burst.dup" : ("wamid.burst." + i);
            String payload = buildPayload(phone, msgId,
                    "burst message #" + i, false);
            tasks.add(new Task(phone, buildRequest(payload)));
        }
        System.out.printf("Burst mode: %d messages to phone %s (50%% duplicates), %d threads%n%n",
                count, phone, threads);
        executeConcurrently(tasks);
    }

    /** 阶梯增压：逐步提升并发找到系统瓶颈 */
    private void runRamp() throws Exception {
        List<String> pool = generatePhonePool(poolSize);
        System.out.printf("Ramp mode: step=%d, max=%d, pool=%d%n%n", rampStep, rampMax, poolSize);
        System.out.printf("%-10s %-10s %-10s %-10s %-10s%n",
                "threads", "msgs", "success", "errors", "avg_ms");
        System.out.println("------------------------------------------------------------");

        for (int t = rampStep; t <= rampMax; t += rampStep) {
            int msgCount = t * 3;
            List<Task> tasks = new ArrayList<>();
            for (int i = 0; i < msgCount; i++) {
                String phone = pool.get(i % pool.size());
                String payload = buildPayload(phone, "wamid.ramp." + t + "." + i,
                        "ramp t=" + t + " msg=" + i, false);
                tasks.add(new Task(phone, buildRequest(payload)));
            }

            resetStats();
            Instant start = Instant.now();
            executeConcurrently(tasks, t);
            Duration d = Duration.between(start, Instant.now());

            long avgLatency = latencies.isEmpty() ? 0
                    : (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            System.out.printf("%-10d %-10d %-10d %-10d %-10d%n",
                    t, msgCount, successCount.get(), errorCount.get(), avgLatency);

            if (errorCount.get() > msgCount * 0.3) {
                System.out.printf("%n⚠  Error rate > 30%% at %d threads, stopping ramp.%n", t);
                break;
            }
            Thread.sleep(1000);
        }
    }

    // ═══════════════════════════════════════════════════
    // 并发执行
    // ═══════════════════════════════════════════════════

    private void executeConcurrently(List<Task> tasks) throws Exception {
        executeConcurrently(tasks, threads);
    }

    private void executeConcurrently(List<Task> tasks, int threadCount) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(tasks.size());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            for (Task task : tasks) {
                executor.submit(() -> {
                    try {
                        if (intervalMs > 0) Thread.sleep(intervalMs);
                        long t0 = System.nanoTime();
                        HttpResponse<String> resp = client.send(task.request,
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
                        latencies.add(latencyMs);
                        totalBytes.addAndGet(resp.body() == null ? 0 : resp.body().length());
                        statusCodes.computeIfAbsent(String.valueOf(resp.statusCode()),
                                k -> new AtomicInteger()).incrementAndGet();
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                            System.err.printf("[ERR] phone=%s status=%d body=%s%n",
                                    task.phone, resp.statusCode(), resp.body());
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        System.err.printf("[ERR] phone=%s exception=%s%n",
                                task.phone, e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(5, TimeUnit.MINUTES);
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ═══════════════════════════════════════════════════
    // 统计报告
    // ═══════════════════════════════════════════════════

    private void printReport(Duration duration) {
        int success = successCount.get();
        int errors = errorCount.get();
        int total = success + errors;

        System.out.println();
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Batch Webhook Simulation Report");
        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("  Mode:           %s%n", mode);
        System.out.printf("  Total messages: %d%n", total);
        System.out.printf("  Threads:        %d%n", threads);
        System.out.printf("  Duration:       %d.%03ds%n",
                duration.toSeconds(), duration.toMillisPart());
        System.out.printf("  Success:        %d (%.1f%%)%n",
                success, total > 0 ? 100.0 * success / total : 0);
        System.out.printf("  Errors:         %d (%.1f%%)%n",
                errors, total > 0 ? 100.0 * errors / total : 0);
        System.out.printf("  Throughput:     %.1f msg/s%n",
                total * 1000.0 / Math.max(1, duration.toMillis()));

        if (!statusCodes.isEmpty()) {
            System.out.println();
            System.out.println("  --- HTTP Status Codes ---");
            statusCodes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> System.out.printf("  HTTP %s: %d%n",
                            e.getKey(), e.getValue().get()));
        }

        if (!latencies.isEmpty()) {
            List<Long> sorted = latencies.stream().sorted().toList();
            int sz = sorted.size();
            System.out.println();
            System.out.println("  --- Latency (ms) ---");
            System.out.printf("  Min:  %d%n", sorted.get(0));
            System.out.printf("  Avg:  %d%n",
                    (long) sorted.stream().mapToLong(Long::longValue).average().orElse(0));
            System.out.printf("  P50:  %d%n", sorted.get(sz / 2));
            System.out.printf("  P95:  %d%n", sorted.get((int) (sz * 0.95)));
            System.out.printf("  P99:  %d%n", sorted.get((int) (sz * 0.99)));
            System.out.printf("  Max:  %d%n", sorted.get(sz - 1));
        }

        // 分配分布查询
        if (dbUrl != null && !dbUrl.isBlank()) {
            try {
                queryAssignmentDistribution();
            } catch (Exception e) {
                System.out.printf("%n  [WARN] DB query failed: %s%n", e.getMessage());
            }
        }

        System.out.println("═══════════════════════════════════════════════");

        if (errors > 0) {
            System.out.printf("%n⚠  %d errors detected — check logs above.%n", errors);
        }
    }

    private void queryAssignmentDistribution() throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT agent_id, COUNT(*) AS cnt FROM assignment_record " +
                    "WHERE status = 'SERVING' GROUP BY agent_id ORDER BY cnt DESC");
            System.out.println();
            System.out.println("  --- Assignment Distribution (SERVING) ---");
            boolean hasData = false;
            while (rs.next()) {
                hasData = true;
                System.out.printf("  Agent %s: %d sessions%n",
                        rs.getString("agent_id"), rs.getInt("cnt"));
            }
            if (!hasData) System.out.println("  (no SERVING assignments)");
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════

    private List<String> generatePhonePool(int size) {
        List<String> pool = new ArrayList<>();
        long base = Long.parseLong(phonePrefix) * 100;
        for (int i = 0; i < size; i++) {
            pool.add(String.valueOf(base + i));
        }
        return pool;
    }

    private static String filler(int i) {
        return "padding-" + "x".repeat(Math.min(i % 50, 20));
    }

    private void resetStats() {
        successCount.set(0);
        errorCount.set(0);
        latencies.clear();
        statusCodes.clear();
        totalBytes.set(0);
    }

    record Task(String phone, HttpRequest request) {}

    // ═══════════════════════════════════════════════════
    // Payload 构建
    // ═══════════════════════════════════════════════════

    private String buildPayload(String from, String msgId, String textBody,
                                boolean withReferral) {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String referralJson = withReferral
                ? """
                  ,"referral": {
                    "source_url": "https://example.com/ad",
                    "source_id": "ad_123456",
                    "source_type": "ad",
                    "body": "Ad primary text demo",
                    "headline": "Ad headline demo",
                    "media_type": "image",
                    "image_url": "https://example.com/ad-image.jpg",
                    "video_url": "https://example.com/ad-video.mp4",
                    "thumbnail_url": "https://example.com/ad-video-thumb.jpg",
                    "ctwa_clid": "ctwa_click_id_demo",
                    "welcome_message": {
                      "text": "Hi, I came from your ad"
                    }
                  }
                  """
                : "";
        return """
               {
                 "object": "whatsapp_business_account",
                 "entry": [
                   {
                     "id": "WABA_ID_DEMO",
                     "changes": [
                       {
                         "field": "messages",
                         "value": {
                           "messaging_product": "whatsapp",
                           "metadata": {
                             "display_phone_number": "15551234567",
                             "phone_number_id": "%s"
                           },
                           "contacts": [
                             {
                               "profile": {
                                 "name": "Batch Test User"
                               },
                               "wa_id": "%s"
                             }
                           ],
                           "messages": [
                             {
                               "from": "%s",
                               "id": "%s",
                               "timestamp": "%s",
                               "type": "text",
                               "text": {
                                 "body": "%s"
                               }%s
                             }
                           ]
                         }
                       }
                     ]
                   }
                 ]
               }
               """.formatted(businessId, from, from, msgId, ts, textBody, referralJson);
    }

    private HttpRequest buildRequest(String payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    // ═══════════════════════════════════════════════════
    // 参数解析
    // ═══════════════════════════════════════════════════

    private static Map<String, String> argsToMap(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        ? args[++i] : "true";
                map.put(key, value);
            }
        }
        return map;
    }

    private void parseArgs(Map<String, String> args) {
        if (args.containsKey("url"))          webhookUrl = args.get("url");
        if (args.containsKey("mode"))         mode = args.get("mode").toLowerCase();
        if (args.containsKey("count"))        count = Integer.parseInt(args.get("count"));
        if (args.containsKey("threads"))      threads = Integer.parseInt(args.get("threads"));
        if (args.containsKey("pool-size"))    poolSize = Integer.parseInt(args.get("pool-size"));
        if (args.containsKey("phone-prefix")) phonePrefix = args.get("phone-prefix");
        if (args.containsKey("interval"))     intervalMs = Long.parseLong(args.get("interval"));
        if (args.containsKey("ramp-step"))    rampStep = Integer.parseInt(args.get("ramp-step"));
        if (args.containsKey("ramp-max"))     rampMax = Integer.parseInt(args.get("ramp-max"));
        if (args.containsKey("fat-count"))    fatCount = Integer.parseInt(args.get("fat-count"));
        if (args.containsKey("hot-count"))    hotCount = Integer.parseInt(args.get("hot-count"));
        if (args.containsKey("db-url"))       dbUrl = args.get("db-url");
        if (args.containsKey("db-user"))      dbUser = args.get("db-user");
        if (args.containsKey("db-pass"))      dbPass = args.get("db-pass");
        if (args.containsKey("business-id"))  businessId = args.get("business-id");
    }

    private void printBanner() {
        System.out.println("+------------------------------------------+");
        System.out.println("|   Webhook Batch Simulator                |");
        System.out.println("+------------------------------------------+");
        System.out.printf("|  URL:       %-30s|%n", webhookUrl);
        System.out.printf("|  Mode:      %-30s|%n", mode);
        System.out.printf("|  Prefix:    %-30s|%n", phonePrefix);
        System.out.printf("|  Pool:      %-30d|%n", poolSize);
        System.out.println("+------------------------------------------+");
        System.out.println();
    }
}
