package com.febrie.backend.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.febrie.backend.media.application.ProcessingDeadline;
import com.febrie.backend.media.domain.FileRecord;
import com.febrie.backend.media.domain.MediaIds;
import com.febrie.backend.media.domain.MediaPurpose;
import com.febrie.backend.media.domain.MediaStatus;
import com.febrie.backend.media.domain.ReferenceType;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import com.febrie.backend.media.infrastructure.persistence.NotificationFiles;
import com.febrie.backend.media.infrastructure.persistence.entity.MediaFileEntity;
import com.febrie.backend.media.infrastructure.persistence.mapper.MediaFileMapper;
import com.febrie.backend.media.infrastructure.persistence.notification.NotificationFileMapper;
import com.febrie.backend.shared.persistence.PersistenceConfiguration;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.DatabaseFixtureMapper;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import com.febrie.backend.user.infrastructure.persistence.mapper.UserMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 仅连接专用 MySQL 库；不扫描 Web、Redis 业务或对象存储，不依赖 MinIO 才能验证事务。 */
@SpringBootTest(classes = MysqlPersistenceTests.DatabaseApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "persistence-test"})
@EnabledIfEnvironmentVariable(named = "IT_DB_PASSWORD", matches = ".+")
class MysqlPersistenceTests {
    @Configuration(proxyBeanMethods = false)
    // 仅专项测试启用此配置，避免其他 SpringBootTest 扫描到精简应用配置。
    @Profile("persistence-test")
    @EnableAutoConfiguration
    @Import({PersistenceConfiguration.class, PersistenceTestConfiguration.class,
            AccountRepository.class, FileRepository.class, NotificationFiles.class})
    static class DatabaseApplication { }

    @Autowired AccountRepository accounts;
    @Autowired FileRepository files;
    @Autowired NotificationFiles notifications;
    @Autowired MediaFileMapper fileMapper;
    @Autowired UserMapper userMapper;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationContext context;
    @Autowired SqlSessionTemplate session;
    private long owner;

    @DynamicPropertySource
    static void requireTestDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/easy_community_auth_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("持久层回归只允许 easy_community_*test* 专用数据库");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @BeforeEach
    void createOwner() {
        owner = transaction().execute(status -> accounts.insert(username(), Role.USER, "test-hash"));
    }

    @AfterEach
    void cleanup() {
        // 按外键顺序只清理本次用户及其文件，不使用全表 DELETE 或清库。
        fixtures.clearAvatar(owner);
        fixtures.deleteOwnedReferences(owner);
        fixtures.deleteOwnedFiles(owner);
        fixtures.deleteIdentities(owner);
        fixtures.deleteUser(owner);
    }

    @Test
    void generatedKeysDefaultsAndCredentialRollbackRemainAtomic() {
        assertTrue(owner > 0);
        assertEquals(1, fixtures.countIdentities(owner));
        var profile = accounts.profile(owner, false).orElseThrow();
        assertEquals(Role.USER, profile.role());
        assertEquals("ACTIVE", profile.status());
        assertEquals(1, profile.authVersion());
        assertNull(profile.avatarMediaId());
        var row = userMapper.selectById(owner);
        assertNotNull(row.getCreatedAt());
        assertNotNull(row.getUpdatedAt());
        assertNull(row.getDeletedAt());

        String name = profile.username();
        assertThrows(DuplicateKeyException.class, () -> transaction().executeWithoutResult(status ->
                accounts.insert(name, Role.USER, "duplicate")));
        assertEquals(1, fixtures.countIdentities(owner));
        assertThrows(IllegalStateException.class, () -> transaction().executeWithoutResult(status -> {
            accounts.lockById(owner).orElseThrow();
            accounts.updatePassword(owner, "changed");
            throw new IllegalStateException("模拟改密后回滚");
        }));
        assertEquals("test-hash", accounts.findByUsername(name).orElseThrow().passwordHash());
        assertEquals(1, accounts.authState(owner).orElseThrow().authVersion());

        String rolledBack = username();
        assertThrows(IllegalStateException.class, () -> transaction().executeWithoutResult(status -> {
            accounts.insert(rolledBack, Role.PATROLLER, "rolled-back");
            throw new IllegalStateException("模拟身份创建后回滚");
        }));
        assertTrue(accounts.findByUsername(rolledBack).isEmpty());
    }

    @Test
    void uuidEnumsUtcMillisecondsAndExplicitNullRoundTrip() {
        String id = issued(MediaPurpose.AVATAR);
        var row = fileMapper.selectOne(Wrappers.<MediaFileEntity>lambdaQuery()
                .eq(MediaFileEntity::getId, MediaIds.bytes(id)));
        assertArrayEquals(MediaIds.bytes(id), row.getId());
        assertEquals(MediaPurpose.AVATAR, row.getPurpose());
        assertEquals(MediaStatus.ISSUED, row.getStatus());
        assertNull(row.getMimeType());
        assertNull(row.getSizeBytes());
        assertEquals(123_000_000, row.getCreatedAt().getNano());
        assertEquals(row.getCreatedAt(), files.find(id).orElseThrow().createdAt());

        accounts.updateAvatar(owner, id);
        assertEquals(id, accounts.profile(owner, false).orElseThrow().avatarMediaId());
        assertEquals(id, transaction().execute(status -> accounts.profile(owner, true).orElseThrow().avatarMediaId()));
        accounts.updateAvatar(owner, null);
        assertNull(accounts.profile(owner, false).orElseThrow().avatarMediaId());
        assertTrue(notifications.ready(id, 123, null, Instant.now(), null, deadline()));
        var ready = files.find(id).orElseThrow();
        assertEquals(MediaStatus.READY, ready.status());
        assertEquals(123L, ready.sizeBytes());
        assertNull(ready.mimeType());
        assertNull(ready.nextActionAt());
    }

    @Test
    void referenceKeysForeignKeysSchedulingAndRollbackRemainIntact() {
        String id = issued(MediaPurpose.ARTICLE_IMAGE);
        fixtures.makeReady(MediaIds.bytes(id), 100);
        transaction().executeWithoutResult(status -> {
            files.lock(id).orElseThrow();
            files.addReference(ReferenceType.ARTICLE_IMAGE, 11, id);
            files.addReference(ReferenceType.ARTICLE_IMAGE, 12, id);
        });
        assertEquals(Set.of(id), files.references(ReferenceType.ARTICLE_IMAGE, 11));
        assertNull(files.find(id).orElseThrow().nextActionAt());
        assertThrows(DataIntegrityViolationException.class, () -> fixtures.deleteFile(MediaIds.bytes(id)));
        files.removeReference(ReferenceType.USER_AVATAR, 11, id);
        assertEquals(2, fixtures.countReferences(MediaIds.bytes(id)));

        assertThrows(IllegalStateException.class, () -> transaction().executeWithoutResult(status -> {
            files.lock(id).orElseThrow();
            files.removeReference(ReferenceType.ARTICLE_IMAGE, 11, id);
            throw new IllegalStateException("模拟引用替换回滚");
        }));
        assertEquals(2, fixtures.countReferences(MediaIds.bytes(id)));
        files.deleting(id, Instant.now());
        files.deleteMetadata(id);
        assertTrue(files.find(id).isPresent());
        files.removeReference(ReferenceType.ARTICLE_IMAGE, 11, id);
        files.removeReference(ReferenceType.ARTICLE_IMAGE, 12, id);
        files.deleteMetadata(id);
        assertTrue(files.find(id).isEmpty());
    }

    @Test
    void readCommittedQueriesSeeOtherTransactionsDespiteRepeatedReads() throws Exception {
        String id = issued(MediaPurpose.ARTICLE_IMAGE);
        fixtures.makeReady(MediaIds.bytes(id), 100);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                assertFalse(files.hasReferences(id));
                assertTrue(files.references(ReferenceType.ARTICLE_IMAGE, 21).isEmpty());
                long version = accounts.authState(owner).orElseThrow().authVersion();
                try {
                    executor.submit(() -> transaction().executeWithoutResult(other -> {
                        files.addReference(ReferenceType.ARTICLE_IMAGE, 21, id);
                        accounts.updateRole(owner, Role.PATROLLER);
                    })).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("并发提交失败", failure);
                }
                assertTrue(files.hasReferences(id));
                assertEquals(Set.of(id), files.references(ReferenceType.ARTICLE_IMAGE, 21));
                assertEquals(version + 1, accounts.authState(owner).orElseThrow().authVersion());
            });
        }
    }

    @Test
    void notificationCommitConditionsAndLateDeadlineKeepOriginalSemantics() {
        String id = issued(MediaPurpose.ARTICLE_IMAGE);
        AtomicInteger reads = new AtomicInteger();
        // 构造与入口检查均在预算内，SQL 已提交后的检查恰好到期。
        var late = new ProcessingDeadline(Duration.ofSeconds(1),
                () -> reads.getAndIncrement() < 2 ? 0 : 1_000_000_000L);
        assertThrows(ProcessingDeadline.TimedOut.class, () ->
                notifications.ready(id, 321, "image/png", Instant.now(), Instant.now().plusSeconds(60), late));
        assertEquals(MediaStatus.READY, files.find(id).orElseThrow().status());
        assertEquals(321L, files.find(id).orElseThrow().sizeBytes());
        assertFalse(notifications.ready(id, 456, null, Instant.now(), null, deadline()));

        String expired = issued(MediaPurpose.AVATAR);
        fixtures.expireFile(MediaIds.bytes(expired));
        assertFalse(notifications.ready(expired, 100, null, Instant.now(), null, deadline()));
        assertEquals(MediaStatus.ISSUED, files.find(expired).orElseThrow().status());
        transaction().executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> notifications.find(expired, deadline())));
    }

    @Test
    void notificationUsesPrivatePoolAndBoundedWaitWithoutBlockingMainPool() throws Exception {
        assertEquals(1, context.getBeansOfType(DataSource.class).size());
        assertEquals(1, context.getBeansOfType(SqlSessionFactory.class).size());
        assertTrue(context.getBeansOfType(NotificationFileMapper.class).isEmpty());
        assertFalse(session.getConfiguration().hasMapper(NotificationFileMapper.class));
        assertEquals(LocalCacheScope.STATEMENT, session.getConfiguration().getLocalCacheScope());
        assertFalse(session.getConfiguration().isCacheEnabled());

        String id = issued(MediaPurpose.AVATAR);
        assertTrue(notifications.find(id, deadline()).isPresent());
        var pool = (HikariDataSource) ReflectionTestUtils.getField(notifications, "source");
        assertNotNull(pool);
        assertEquals(250, pool.getConnectionTimeout());
        assertTrue(pool.isAutoCommit());
        List<Connection> occupied = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) { occupied.add(pool.getConnection()); }
            assertTrue(files.find(id).isPresent());
            assertTimeout(Duration.ofSeconds(3), () ->
                    assertThrows(ProcessingDeadline.TimedOut.class, () -> notifications.find(id, deadline())));
        } finally {
            for (Connection connection : occupied) { connection.close(); }
        }
    }

    @Test
    void lockedFileMakesReadyTimeoutAndCannotCommitLater() throws Exception {
        String id = issued(MediaPurpose.AVATAR);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> transaction().executeWithoutResult(status -> {
                files.lock(id).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("等待释放锁超时"); }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(4), () -> assertThrows(ProcessingDeadline.TimedOut.class, () ->
                        notifications.ready(id, 100, null, Instant.now(), Instant.now().plusSeconds(60), deadline())));
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertEquals(MediaStatus.ISSUED, files.find(id).orElseThrow().status());
        // 超时取消可能使旧物理连接失效；下一次通知必须能正常使用连接池。
        assertTrue(notifications.find(id, deadline()).isPresent());
    }

    @Test
    void candidatesUseStableOrderingAndDeletingRetriesRespectState() {
        Instant due = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        String first = issued(MediaPurpose.ARTICLE_IMAGE);
        String second = issued(MediaPurpose.ARTICLE_IMAGE);
        files.deleting(first, due);
        files.deleting(second, due);
        List<String> ordered = new ArrayList<>(List.of(first, second));
        ordered.sort(String::compareTo);
        assertEquals(ordered, files.candidates(MediaStatus.DELETING, Instant.now(), 2));
        assertEquals(ordered.subList(0, 1), files.candidates(MediaStatus.DELETING, Instant.now(), 1));
        Instant retry = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        files.rescheduleDeletion(first, retry);
        assertEquals(retry, files.find(first).orElseThrow().nextActionAt());
        String issued = issued(MediaPurpose.AVATAR);
        Instant unchanged = files.find(issued).orElseThrow().nextActionAt();
        files.rescheduleDeletion(issued, retry);
        assertEquals(unchanged, files.find(issued).orElseThrow().nextActionAt());
    }

    private String issued(MediaPurpose purpose) {
        String id = UUID.randomUUID().toString();
        Instant created = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(123);
        files.insert(new FileRecord(id, owner, purpose, MediaStatus.ISSUED, null, null,
                created, created.plusSeconds(7200)));
        return id;
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private static ProcessingDeadline deadline() { return new ProcessingDeadline(Duration.ofSeconds(10)); }

    private static String username() { return "mp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
}
