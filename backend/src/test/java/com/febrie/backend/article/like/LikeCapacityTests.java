package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.maintenance.LikeAuditService;
import com.febrie.backend.article.like.application.maintenance.LikeRecoveryService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import com.febrie.backend.BackendApplication;
import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 两个真实应用实例的受控容量实验；阈值只在测试实例提高，不修改生产限流。 */
@EnabledIfEnvironmentVariable(named = "IT_LIKE_LOAD", matches = "true")
class LikeCapacityTests {
    private final List<ConfigurableApplicationContext> applications = new ArrayList<>();
    private final List<Client> clients = new ArrayList<>();
    private final List<Long> users = new ArrayList<>(), articles = new ArrayList<>();
    private final ConcurrentMap<Pending, Long> pending = new ConcurrentHashMap<>();
    private final String prefix = "ec:test:load:" + UUID.randomUUID();
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private int[] ports;
    private String namespace;
    private AtomicLong oldest = new AtomicLong();

    @Test void measureUniformHotColdMixedAndBacklogAcrossTwoApplications() throws Exception {
        String url = System.getenv("IT_DB_URL"), vhost = System.getenv("IT_RABBITMQ_VHOST");
        assertNotNull(url); assertTrue(url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?"));
        assertNotNull(vhost); assertTrue(vhost.contains("test"));
        int seconds = Integer.parseInt(System.getenv().getOrDefault("IT_LIKE_LOAD_SECONDS", "15"));
        assertTrue(seconds >= 3 && seconds <= 300);
        try {
            var first = start();
            jdbc = first.getBean(JdbcTemplate.class); mapper = first.getBean(ObjectMapper.class);
            var properties = first.getBean(LikeProperties.class); namespace = properties.namespace();
            var maintenance = new LikeProperties(namespace, Duration.ofSeconds(2), 128, 2, 20,
                    67108864, 100000, 4, Duration.ofMillis(300), "none", -1, 0);
            int epoch = jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1", Integer.class);
            first.getBean(LikeRecoveryService.class); // 确认实际应用装配了同一维护服务。
            new LikeRecoveryService(first.getBean(DataSource.class), first.getBean(LikeVersionAllocator.class), maintenance).recover(epoch);
            var second = start();
            assertEquals(epoch + 1, Integer.parseInt(second.getBean(LikeVersionAllocator.class).status().get(0)), "普通实例启动不得提升纪元");
            ports = applications.stream().mapToInt(app -> app.getEnvironment().getRequiredProperty("local.server.port", Integer.class)).toArray();
            seed(first);
            List<Map<String, Object>> measurements = new ArrayList<>();
            try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
                sampler.scheduleWithFixedDelay(this::samplePending, 0, 250, TimeUnit.MILLISECONDS);
                for (String scenario : List.of("uniform", "hot")) {
                    for (int rate : List.of(20, 60, 120, 240)) { measurements.add(stage(scenario, rate, seconds)); }
                }
                // 先完整预热访问矩阵，再分别测热读取与清缓存后的冷启动读取。
                for(var client:clients){for(long article:articles){assertEquals(200,client.request(0,article,true).statusCode());}}
                measurements.add(stage("warm",60,seconds));
                // 删除的只是本次实验的展示缓存，不触碰签发器或共享Redis数据。
                clearDisplayCache(first);
                measurements.add(stage("cold", 60, seconds));
                measurements.add(stage("mixed", 60, seconds));
                for (var app : applications) { app.getBean("likeListener", SimpleMessageListenerContainer.class).stop(); }
                measurements.add(stage("backlog", 60, Math.min(5, seconds)));
                long resumed = System.nanoTime();
                for (var app : applications) { app.getBean("likeListener", SimpleMessageListenerContainer.class).start(); }
                await().atMost(Duration.ofSeconds(60)).until(() -> { samplePending(); return pending.isEmpty(); });
                measurements.add(Map.of("scenario", "backlog_recovery", "milliseconds", (System.nanoTime() - resumed) / 1_000_000));
            }
            assertTrue(first.getBean(LikeAuditService.class).discrepancies().isEmpty(), "负载结束后关系与计数必须一致");
            var registry = first.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            var timers = registry.find("article.like.duration").timers().stream().map(timer -> Map.of(
                    "action", timer.getId().getTag("action"), "count", timer.count(), "meanMs", timer.mean(TimeUnit.MILLISECONDS),
                    "maxMs", timer.max(TimeUnit.MILLISECONDS))).toList();
            var report = new LinkedHashMap<String, Object>();
            report.put("recordedAt", Instant.now().toString()); report.put("sampleSeconds", seconds);
            report.put("java", System.getProperty("java.version")); report.put("os", System.getProperty("os.name"));
            report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            report.put("mysql", jdbc.queryForObject("SELECT VERSION()", String.class));
            report.put("applications", 2); report.put("consumerCount", 4); report.put("prefetch", 20);
            report.put("maxObservedPendingAgeMs", oldest.get() / 1_000_000); report.put("measurements", measurements);
            report.put("firstApplicationTimers", timers); report.put("sqlAuditConsistent", true);
            report.put("qualification", "本机短窗口容量实验，包含认证SQL；不代表生产SLA或长期极限吞吐");
            Path output = Path.of("target", "like-capacity.json");
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("LIKE_CAPACITY_REPORT " + output.toAbsolutePath());
        } finally { cleanup(); }
    }

    private ConfigurableApplicationContext start() {
        var context = SpringApplication.run(BackendApplication.class,
                "--spring.profiles.active=test", "--server.port=0", "--management.server.port=0",
                "--app.auth.redis-prefix=" + prefix,
                "--app.like.per-minute=100000", "--app.auth.user-per-minute=100000",
                "--app.auth.csrf-per-minute=100000", "--app.auth.login-per-minute=100000",
                "--app.media.cleanup-delay=24h", "--app.auth.cleanup-delay=24h");
        applications.add(context); return context;
    }

    private void seed(ConfigurableApplicationContext context) throws Exception {
        var accounts = context.getBean(AccountRepository.class);
        var transaction = context.getBean(TransactionTemplate.class);
        String hash = context.getBean(PasswordEncoder.class).encode("load-test-password");
        for (int i = 0; i < 16; i++) {
            String name = "ll_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            long user = transaction.execute(s -> accounts.insert(name, Role.USER, hash)); users.add(user);
            var client = new Client(user); clients.add(client); client.login(name);
        }
        var repository = context.getBean(ArticleRepository.class);
        for (int i = 0; i < 32; i++) {
            articles.add(transaction.execute(s -> repository.insert(users.getFirst(),
                    new PreparedArticle("load", "load", "{}", "<p>load</p>", null, Set.of(), List.of()), Instant.now())));
        }
    }

    private Map<String, Object> stage(String scenario, int rate, int seconds) throws Exception {
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger success = new AtomicInteger(), skipped = new AtomicInteger();
        ConcurrentMap<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
        AtomicInteger sequence = new AtomicInteger();
        var lockBefore=lockStatistics();
        long began = System.nanoTime();
        try (var work = Executors.newVirtualThreadPerTaskExecutor(); var clock = Executors.newSingleThreadScheduledExecutor()) {
            var ticking = clock.scheduleAtFixedRate(() -> {
                int number = sequence.getAndIncrement(); Client client = clients.get(number % clients.size());
                if (!client.available.tryAcquire()) { skipped.incrementAndGet(); return; }
                work.submit(() -> {
                    long started = System.nanoTime();
                    try {
                        long article = articles.get(scenario.equals("hot") ? 0 : (number / clients.size()) % articles.size());
                        boolean read = scenario.equals("warm")||scenario.equals("cold")||(scenario.equals("mixed") && number % 2 == 0);
                        var response = client.request(number % ports.length, article, read);
                        statuses.computeIfAbsent(response.statusCode(), ignored -> new AtomicInteger()).incrementAndGet();
                        if (response.statusCode() == (read ? 200 : 202)) {
                            success.incrementAndGet();
                            if (!read) {
                                long version = mapper.readTree(response.body()).path("version").asLong();
                                pending.put(new Pending(client.user, article, version), started);
                            }
                        }
                    } catch (Exception failure) { statuses.computeIfAbsent(0, ignored -> new AtomicInteger()).incrementAndGet(); }
                    finally { latencies.add(System.nanoTime() - started); client.available.release(); }
                });
            }, 0, 1_000_000_000L / rate, TimeUnit.NANOSECONDS);
            new CountDownLatch(1).await(seconds, TimeUnit.SECONDS);
            ticking.cancel(false);
        }
        samplePending();
        var values = latencies.stream().sorted().toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario); result.put("targetQps", rate); result.put("sampleSeconds", seconds);
        result.put("completed", values.size()); result.put("success", success.get()); result.put("skippedBusyClient", skipped.get());
        result.put("actualQps", values.size() / ((System.nanoTime() - began) / 1_000_000_000.0));
        result.put("p95Ms", percentile(values, .95)); result.put("p99Ms", percentile(values, .99));
        result.put("statusCounts", statuses); result.put("acceptedPending", pending.size());
        var lockAfter=lockStatistics();
        result.put("sqlRowLockWaits",lockAfter.getOrDefault("Innodb_row_lock_waits",0L)-lockBefore.getOrDefault("Innodb_row_lock_waits",0L));
        result.put("sqlRowLockWaitMs",lockAfter.getOrDefault("Innodb_row_lock_time",0L)-lockBefore.getOrDefault("Innodb_row_lock_time",0L));
        System.out.println("LIKE_LOAD_STAGE " + mapper.writeValueAsString(result));
        return result;
    }

    private void samplePending() {
        if (pending.isEmpty() || jdbc == null) { return; }
        try {
            Map<String, Long> versions = new HashMap<>();
            String marks = String.join(",", Collections.nCopies(articles.size(), "?"));
            jdbc.query("SELECT user_id,article_id,version FROM article_like_state WHERE article_id IN (" + marks + ")",
                    rs -> { versions.put(rs.getLong(1) + ":" + rs.getLong(2), rs.getLong(3)); }, articles.toArray());
            long now = System.nanoTime();
            pending.forEach((key, sent) -> {
                if (versions.getOrDefault(key.user + ":" + key.article, 0L) >= key.version) { pending.remove(key, sent); }
                else { oldest.accumulateAndGet(now - sent, Math::max); }
            });
        } catch (RuntimeException ignored) { /* 监控失败不能伪造已经完成，保留待确认集合。 */ }
    }

    private static double percentile(List<Long> values, double fraction) {
        return values.isEmpty() ? 0 : values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * fraction) - 1)) / 1_000_000.0;
    }
    private Map<String,Long> lockStatistics(){
        Map<String,Long> result=new HashMap<>();
        jdbc.query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time')",
                rs->{result.put(rs.getString(1),Long.parseLong(rs.getString(2)));});
        return result;
    }
    private void clearDisplayCache(ConfigurableApplicationContext app) {
        var redis = app.getBean(StringRedisTemplate.class);
        try (var keys = redis.scan(ScanOptions.scanOptions().match(namespace + ":cache:v1:*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
    }
    private void cleanup() {
        clients.forEach(Client::close);
        for (var app : applications) { app.getBean("likeListener", SimpleMessageListenerContainer.class).stop(); }
        if (!applications.isEmpty()) {
            var app = applications.getFirst();
            if (namespace != null) {
                var p = app.getBean(LikeProperties.class); var admin = app.getBean(AmqpAdmin.class);
                admin.deleteQueue(p.name("command.q")); admin.deleteQueue(p.name("dead.q"));
                admin.deleteExchange(p.name("command.x")); admin.deleteExchange(p.name("dead.x"));
                var redis = app.getBean(StringRedisTemplate.class);
                try (var keys = redis.scan(ScanOptions.scanOptions().match(prefix + ":*").count(100).build())) { while (keys.hasNext()) { redis.delete(keys.next()); } }
            }
            if (jdbc != null) {
                for (long article : articles) {
                    jdbc.update("DELETE FROM article_like_state WHERE article_id=?", article);
                    jdbc.update("DELETE FROM article_like_count WHERE article_id=?", article);
                    jdbc.update("DELETE FROM article WHERE id=?", article);
                }
                for (long user : users) { jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", user); jdbc.update("DELETE FROM users WHERE id=?", user); }
            }
        }
        for (int i = applications.size() - 1; i >= 0; i--) { applications.get(i).close(); }
    }
    private record Pending(long user, long article, long version) { }

    private class Client implements AutoCloseable {
        final long user;
        final Semaphore available = new Semaphore(1);
        final Map<Long, Boolean> desired = new HashMap<>();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Client(long user) { this.user = user; }
        void login(String name) throws Exception {
            csrf = mapper.readTree(send(0, "GET", "/api/auth/csrf", null, false).body()).path("token").asString();
            assertEquals(200, send(0, "POST", "/api/auth/login", Map.of("username", name, "password", "load-test-password"), true).statusCode());
            csrf = mapper.readTree(send(0, "GET", "/api/auth/csrf", null, false).body()).path("token").asString();
        }
        HttpResponse<String> request(int server, long article, boolean read) throws Exception {
            if (read) { return send(server, "GET", "/api/articles/" + article, null, false); }
            boolean next = !desired.getOrDefault(article, false); desired.put(article, next);
            return send(server, next ? "PUT" : "DELETE", "/api/articles/" + article + "/like", null, true);
        }
        HttpResponse<String> send(int server, String method, String path, Object body, boolean token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[server] + path)).timeout(Duration.ofSeconds(10));
            if (token) { request.header("X-CSRF-TOKEN", csrf); }
            if (path.endsWith("/like")) { request.header("X-Operation-Id", UUID.randomUUID().toString()); }
            if (body != null) { request.header("Content-Type", "application/json"); }
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { http.close(); }
    }
}
