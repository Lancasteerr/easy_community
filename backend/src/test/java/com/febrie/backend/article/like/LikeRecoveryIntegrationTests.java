package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.maintenance.LikeRecoveryService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import com.zaxxer.hikari.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实命名锁、持久纪元与Redis维护；维护失败可以留下纪元空洞，不能重用已保留纪元。 */
@EnabledIfEnvironmentVariable(named = "IT_LIKE", matches = "true")
class LikeRecoveryIntegrationTests {
    HikariDataSource dataSource;
    JdbcTemplate jdbc;
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    LikeVersionAllocator allocator;
    LikeRecoveryService recovery;
    String url;

    @BeforeEach void setup() {
        url = System.getenv("IT_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) { throw new IllegalArgumentException("恢复测试仅允许专用数据库"); }
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(System.getenv("IT_DB_USERNAME"));
        config.setPassword(System.getenv("IT_DB_PASSWORD")); config.setMaximumPoolSize(3);
        dataSource = new HikariDataSource(config); jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        var server = new RedisStandaloneConfiguration(System.getenv().getOrDefault("IT_REDIS_HOST", "127.0.0.1"), Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379")));
        server.setPassword(System.getenv().getOrDefault("IT_REDIS_PASSWORD", ""));
        factory = new LettuceConnectionFactory(server); factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
        var p = new LikeProperties("ec:test:" + UUID.randomUUID() + ":like", Duration.ofSeconds(2), 128, 2, 20, 67108864, 60, 4, Duration.ofMillis(300), "none", -1, 0);
        allocator = spy(new LikeVersionAllocator(redis, p, mock(LikeTelemetry.class)));
        recovery = new LikeRecoveryService(dataSource, allocator, p);
    }
    @AfterEach void close() {
        if (allocator != null) { redis.delete(allocator.keys()); }
        if (factory != null) { factory.destroy(); }
        if (dataSource != null) { dataSource.close(); }
    }
    int epoch() { return jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1", Integer.class); }

    @Test void wrongExpectedEpochDoesNotMutateAndFailureAfterCommitRequiresAnotherEpoch() throws Exception {
        int before = epoch();
        assertThrows(IllegalStateException.class, () -> recovery.recover(before + 1)); assertEquals(before, epoch());
        doThrow(new IllegalStateException("恢复进程在SQL提交后失败")).when(allocator).initialize(anyInt(), anyString());
        assertThrows(IllegalStateException.class, () -> recovery.recover(before));
        assertEquals(before + 1, epoch()); assertEquals("PAUSED", allocator.status().get(2));
        assertThrows(RuntimeException.class, allocator::next);
        doCallRealMethod().when(allocator).initialize(anyInt(), anyString());
        assertEquals(before + 2, recovery.recover(before + 1));
        long issued = allocator.next(); assertEquals(before + 2, issued >>> 43);
        assertEquals(1, issued & ((1L << 43) - 1));
        assertThrows(RuntimeException.class, () -> allocator.initialize(before + 1, allocator.recoveryRuntime()));
        assertThrows(RuntimeException.class, () -> allocator.activate(before + 1, allocator.recoveryRuntime()));
        assertEquals("READY", allocator.status().get(2));
    }

    @Test void competingRecoveryCannotPassDedicatedMysqlNamedLock() throws Exception {
        int before = epoch(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return call.callRealMethod(); })
                .when(allocator).initialize(anyInt(), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> recovery.recover(before));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertThrows(IllegalStateException.class, () -> recovery.recover(before)); }
            finally { release.countDown(); }
            assertEquals(before + 1, first.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void v5BackfillsBothPublicAndHiddenExistingArticles() throws Exception {
        String schema = "easy_community_like_migration_test_" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(schema.matches("easy_community_[a-z0-9_]*test[a-z0-9_]*"));
        String originalSchema = new java.net.URI(url.substring(5)).getPath().substring(1);
        String migratedUrl = url.replace("/" + originalSchema + "?", "/" + schema + "?");
        jdbc.execute("CREATE DATABASE " + schema);
        try {
            Flyway.configure().dataSource(migratedUrl, System.getenv("IT_DB_USERNAME"), System.getenv("IT_DB_PASSWORD"))
                    .target(MigrationVersion.fromVersion("4")).load().migrate();
            try (var connection = java.sql.DriverManager.getConnection(migratedUrl, System.getenv("IT_DB_USERNAME"), System.getenv("IT_DB_PASSWORD")); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users(id,username,role) VALUES(1,'migration_user','USER')");
                statement.executeUpdate("INSERT INTO article(id,author_id,title,summary,content_json,content_html,hidden,created_at,updated_at,published_at) VALUES"
                        + "(1,1,'public','s','{}','p',0,NOW(3),NOW(3),NOW(3)),(2,1,'hidden','s','{}','p',1,NOW(3),NOW(3),NOW(3))");
            }
            Flyway.configure().dataSource(migratedUrl, System.getenv("IT_DB_USERNAME"), System.getenv("IT_DB_PASSWORD")).load().migrate();
            try (var connection = java.sql.DriverManager.getConnection(migratedUrl, System.getenv("IT_DB_USERNAME"), System.getenv("IT_DB_PASSWORD")); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*),SUM(like_count) FROM article_like_count")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertEquals(0, rows.getLong(2));
            }
        } finally { jdbc.execute("DROP DATABASE " + schema); }
    }
}
