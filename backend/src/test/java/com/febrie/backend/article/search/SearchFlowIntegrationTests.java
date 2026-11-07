package com.febrie.backend.article.search;

import com.febrie.backend.article.content.application.*;
import com.febrie.backend.article.content.application.dto.SaveArticleCommand;
import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.search.infrastructure.elasticsearch.*;
import com.febrie.backend.article.search.infrastructure.messaging.*;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立四服务上的真实提交→Outbox→MQ→ES→HTTP闭环，只清本次随机资源。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_SEARCH", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchFlowIntegrationTests {
    private static final String PREFIX = "ec:test:searchflow:" + UUID.randomUUID();
    private static SearchEsFixture fixture;
    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("IT_DB_URL");
        if (url == null || !url.contains("/easy_community_search_test?")) { throw new IllegalArgumentException("闭环测试只允许检索专用数据库"); }
        fixture = new SearchEsFixture();
        registry.add("app.auth.redis-prefix", () -> PREFIX);
        registry.add("app.search.uri", () -> fixture.p.uri().toString());
        registry.add("app.search.username", () -> fixture.p.username());
        registry.add("app.search.password", () -> fixture.p.password());
        registry.add("app.search.index-name", () -> fixture.index);
        registry.add("app.search.read-alias", () -> fixture.p.readAlias());
        registry.add("app.search.write-alias", () -> fixture.p.writeAlias());
        registry.add("app.search.query-enabled", () -> true);
        registry.add("app.search.cursor-secret", () -> "flow-test-shared-secret-32-bytes-123456");
        registry.add("app.search.rank-scheduling-enabled", () -> false);
    }
    @LocalServerPort int port;
    @Autowired ArticleService articles;
    @Autowired ArticleRankService ranks;
    @Autowired ArticleIndexStore index;
    @Autowired ArticleIndexAdmin indexAdmin;
    @Autowired ArticleIndexMaintenance maintenance;
    @Autowired SearchProperties p;
    @Autowired IndexDeadLetterReplay replay;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired AccountRepository accounts;
    @Autowired TransactionTemplate transaction;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired ArticleFixtureMapper articleFixtures;
    @Autowired StringRedisTemplate redis;
    @Autowired ArticleCache cache;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired HikariDataSource source;
    @Autowired com.febrie.backend.article.view.application.ArticleViewFlush viewFlush;
    @Autowired @Qualifier("articleIndexListener") SimpleMessageListenerContainer listener;
    @Autowired @Qualifier("likeListener") SimpleMessageListenerContainer likeListener;
    @Autowired @Qualifier("articleIndexRelayScheduler") ThreadPoolTaskScheduler relayScheduler;
    @MockitoSpyBean ArticleReader cardReader;
    @MockitoBean UploadStorage storage;
    private UserActor actor;
    private final List<Long> ids = new ArrayList<>();
    private final List<String> extraIndices = new ArrayList<>();
    private HttpClient http;
    private RabbitAdmin admin;
    @BeforeAll void setup() {
        long owner = Objects.requireNonNull(transaction.execute(s -> accounts.insert("sf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), Role.USER, "{noop}unused")));
        actor = new UserActor(owner, Role.USER, 1);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        admin = new RabbitAdmin(connectionFactory);
    }
    @AfterAll void cleanup() throws Exception {
        relayScheduler.shutdown(); listener.stop(); likeListener.stop();
        if (actor != null) {
            articleFixtures.deleteTagsForAuthor(actor.userId()); articleFixtures.deleteLikeStatesForAuthor(actor.userId());
            articleFixtures.deleteLikeCountsForAuthor(actor.userId()); articleFixtures.deleteArticlesForAuthor(actor.userId());
            fixtures.deleteIdentities(actor.userId()); fixtures.deleteUser(actor.userId());
        }
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
        admin.deleteQueue(p.name("work.q")); admin.deleteQueue(p.name("dead.q"));
        admin.deleteExchange(p.name("change.x")); admin.deleteExchange(p.name("dead.x"));
        // 同一上下文的点赞拓扑也属于本次随机命名空间。
        var like = getLikeProperties();
        admin.deleteQueue(like.name("command.q")); admin.deleteQueue(like.name("dead.q"));
        admin.deleteExchange(like.name("command.x")); admin.deleteExchange(like.name("dead.x"));
        for (String target : extraIndices) { fixture.es.indices().delete(d -> d.index(target).ignoreUnavailable(true)); }
        fixture.close(); http.close();
    }
    @Autowired com.febrie.backend.article.like.infrastructure.config.LikeProperties likeProperties;
    private com.febrie.backend.article.like.infrastructure.config.LikeProperties getLikeProperties() { return likeProperties; }

    @Test @Order(1) void committedArticlesReachSearchAndFeedWithoutCountingListViews() throws Exception {
        long id = create("Java 缓存优化");
        awaitIndexed(id, 1);
        fixture.es.indices().refresh(r -> r.index(p.readAlias()));
        var response = get("/api/articles/search?q=Java");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        var card = json.readTree(response.body()).path("items").get(0);
        assertEquals(Long.toString(id), card.path("articleId").asString());
        assertEquals(0, card.path("viewCount").asLong()); assertFalse(card.path("likedByMe").asBoolean());
        assertEquals(200, get("/api/articles/feed").statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT view_count FROM article WHERE id=?", Long.class, id));
        assertEquals(200, get("/api/articles/" + id).statusCode());
        assertEquals(1, json.readTree(get("/api/articles/feed").body()).path("items").get(0).path("viewCount").asLong());
        articles.hide(actor, id); awaitIndexed(id, 2);
        fixture.es.indices().refresh(r -> r.index(p.readAlias()));
        assertTrue(json.readTree(get("/api/articles/search?q=Java").body()).path("items").isEmpty());
        // 建档后首次算分无需等待五分钟调度。
        long fresh = create("推荐首次算分"); awaitIndexed(fresh, 1);
        await().atMost(Duration.ofSeconds(15)).until(() -> ((Number) fixture.es.get(g -> g.index(p.writeAlias()).id(Long.toString(fresh)), Map.class).source().get("scoreVersion")).longValue() > 0);
    }

    @Test @Order(2) void postCommitProcessExitAndUnroutablePublishRemainRecoverable() throws Exception {
        long id = create("before-crash"); awaitIndexed(id, 1);
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> args = new ArrayList<>(List.of(executable));
        String temporary = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (temporary != null) { args.add("-Djdk.net.unixdomain.tmpdir=" + temporary); }
        args.addAll(List.of("-cp", classpath, "com.febrie.backend.article.content.application.ArticleCrashProcess",
                Long.toString(actor.userId()), Long.toString(id), PREFIX));
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(Path.of("target", "search-crash-child.log").toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        awaitIndexed(id, 2);
        assertEquals("after-crash", fixture.es.get(g -> g.index(p.writeAlias()).id(Long.toString(id)), Map.class).source().get("title"));

        var binding = new Binding(p.name("work.q"), Binding.DestinationType.QUEUE, p.name("change.x"), "article.index.changed", Map.of());
        admin.removeBinding(binding);
        long held;
        try {
            held = create("路由恢复");
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM article_outbox WHERE article_id=? AND claim_token IS NULL AND available_at>CURRENT_TIMESTAMP(3)", Integer.class, held) > 0);
        } finally { admin.declareBinding(binding); }
        jdbc.update("UPDATE article_outbox SET available_at=CURRENT_TIMESTAMP(3) WHERE article_id=?", held);
        awaitIndexed(held, 1);
    }

    @Test @Order(3)
    @EnabledIfEnvironmentVariable(named = "IT_SEARCH_FAULTS", matches = "true")
    void esOutageKeepsBusinessWritesAndDeadLettersCanBeReplayed() throws Exception {
        // 固定测试容器名并检查标签，绝不接受来自请求或业务环境的目标。
        var details = json.readTree(docker("inspect", "easy-community-search-it-es"));
        assertEquals("true", details.get(0).path("Config").path("Labels").path("ec.search.test").asString());
        docker("stop", "easy-community-search-it-es");
        long id;
        try {
            id = create("故障恢复文章");
            assertEquals(503, get("/api/articles/feed").statusCode());
            assertEquals("故障恢复文章", jdbc.queryForObject("SELECT title FROM article WHERE id=?", String.class, id));
            await().atMost(Duration.ofSeconds(35)).until(() -> admin.getQueueInfo(p.name("dead.q")).getMessageCount() > 0);
        } finally { docker("start", "easy-community-search-it-es"); }
        await().ignoreExceptions().atMost(Duration.ofSeconds(60)).until(() -> fixture.es.ping().value());
        assertTrue(replay.replay(100) > 0); awaitIndexed(id, 1);
    }

    @Test @Order(4) void rebuildImportsAuditsRepairsAndAtomicallySwitchesAliases() throws Exception {
        long id = create("重建验证"); awaitIndexed(id, 1);
        String target = fixture.index + "_rebuilt"; extraIndices.add(target);
        indexAdmin.initialize(target, false);
        assertTrue(maintenance.importAll(target) > 0);
        assertEquals(0, maintenance.audit(target, false).differences());
        fixture.es.delete(d -> d.index(target).id(Long.toString(id)));
        assertTrue(maintenance.audit(target, false).differences() > 0);
        assertEquals(0, maintenance.audit(target, true).differences());
        assertEquals(0, ranks.scan(true, target, false).failed());
        fixture.es.indices().refresh(r -> r.index(target));
        indexAdmin.switchAliases(target);
        assertEquals(Set.of(target), fixture.es.indices().getAlias(a -> a.name(p.readAlias())).aliases().keySet());
        long after = create("切换后新增"); awaitIndexed(after, 1);
        assertTrue(fixture.es.get(g -> g.index(target).id(Long.toString(after)), Map.class).found());
    }

    @Test @Order(5) void recordsWarmColdAndBackgroundQueryCosts() throws Exception {
        for (int i = 0; i < 8; i++) { awaitIndexed(create("性能验证" + i), 1); }
        fixture.es.indices().refresh(r -> r.index(p.readAlias()));
        assertEquals(200, get("/api/articles/feed").statusCode());
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(measure("warm", 30, false));
        results.add(measure("cold", 10, true));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var rank = executor.submit(() -> ranks.scan(true, p.writeAlias(), true));
            results.add(measure("background", 30, false)); assertEquals(0, rank.get(30, TimeUnit.SECONDS).failed());
        }
        results.add(observeFlushAndEdit());
        Files.writeString(Path.of("target", "search-performance.json"), json.writeValueAsString(results));
    }
    /** 记录既有刷盘与编辑并发时的实际耗时及专用MySQL全局行锁等待增量，不宣称必定发生竞争。 */
    private Map<String, Object> observeFlushAndEdit() throws Exception {
        long id = ids.getLast();
        assertEquals(200, get("/api/articles/" + id).statusCode());
        var before = rowLockStatus();
        long flushMicros, editMicros;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var flush = workers.submit(() -> {
                barrier.await(); long started = System.nanoTime(); viewFlush.flush();
                return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
            });
            var edit = workers.submit(() -> {
                barrier.await(); long started = System.nanoTime();
                articles.save(actor, new SaveArticleCommand(id, "性能验证编辑", json.readTree(
                        "{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"编辑正文\"}]}]}}"), null, List.of()));
                return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
            });
            flushMicros = flush.get(15, TimeUnit.SECONDS); editMicros = edit.get(15, TimeUnit.SECONDS);
        }
        var after = rowLockStatus();
        awaitIndexed(id, jdbc.queryForObject("SELECT meta_version FROM article WHERE id=?", Long.class, id));
        return Map.of("scenario", "flushAndEdit", "flushMicros", flushMicros, "editMicros", editMicros,
                "innodbRowLockWaitsDelta", after.get("Innodb_row_lock_waits") - before.get("Innodb_row_lock_waits"),
                "innodbRowLockTimeMillisDelta", after.get("Innodb_row_lock_time") - before.get("Innodb_row_lock_time"));
    }
    private Map<String, Long> rowLockStatus() {
        Map<String, Long> result = new HashMap<>();
        jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits')")
                .forEach(row -> result.put(row.get("Variable_name").toString(), Long.parseLong(row.get("Value").toString())));
        return result;
    }
    private Map<String, Object> measure(String scenario, int requests, boolean cold) throws Exception {
        Object observedReader = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(cardReader);
        clearInvocations(observedReader);
        List<Long> timings = new ArrayList<>(); int pendingPeak = 0;
        for (int i = 0; i < requests; i++) {
            if (cold) { ids.forEach(id -> redis.delete(cache.key(id, "list"))); }
            long started = System.nanoTime();
            var response = get("/api/articles/feed");
            timings.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
            assertEquals(200, response.statusCode(), response.body());
            pendingPeak = Math.max(pendingPeak, source.getHikariPoolMXBean().getThreadsAwaitingConnection());
        }
        timings.sort(Long::compare);
        long batches = mockingDetails(observedReader).getInvocations().stream().filter(i -> i.getMethod().getName().equals("lists")).count();
        if (!cold) { assertEquals(0, batches, "热卡片不应额外查询主库状态"); }
        else { assertTrue(batches > 0); }
        return Map.of("scenario", scenario, "requests", requests, "p95Micros", timings.get((int) Math.ceil(requests * .95) - 1),
                "p99Micros", timings.get((int) Math.ceil(requests * .99) - 1), "cardSqlBatches", batches, "observedPoolWaiters", pendingPeak);
    }
    private long create(String title) {
        var body = json.readTree("{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"正文\"}]}]}}");
        long id = articles.save(actor, new SaveArticleCommand(null, title, body, null, List.of())); ids.add(id); return id;
    }
    private void awaitIndexed(long id, long version) {
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).until(() -> index.metaVersion(id) >= version);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("SELECT COUNT(*) FROM article_outbox WHERE article_id=?", Integer.class, id) == 0);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String docker(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var drain = Executors.newSingleThreadExecutor()) {
            // inspect输出可能超过Windows管道缓冲，必须并行读取后再等待进程，不能先waitFor。
            var output = drain.submit(() -> new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("测试容器操作超时"); }
            if (process.exitValue() != 0) { throw new IllegalStateException("测试容器操作失败"); }
            return output.get(5, TimeUnit.SECONDS);
        }
    }
}
