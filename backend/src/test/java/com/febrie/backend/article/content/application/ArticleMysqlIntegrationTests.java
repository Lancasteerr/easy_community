package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.content.application.dto.SaveArticleCommand;

import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.article.tag.infrastructure.persistence.TagRepository;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.*;
import java.sql.*;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式启用的真实MySQL事务测试；屏障控制时序，不靠随机sleep证明并发正确。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class ArticleMysqlIntegrationTests {
    private static final String PREFIX = "ec:test:article:" + UUID.randomUUID();
    @Autowired ArticleCommitService commits;
    @Autowired ArticleService service;
    @Autowired ArticleReader reader;
    @Autowired ArticleQueryService queries;
    @Autowired com.febrie.backend.article.content.infrastructure.cache.ArticleCache cache;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired TagRepository tagRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accounts;
    @Autowired FileRepository files;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired ArticleFixtureMapper articleFixtures;
    @Autowired TransactionTemplate transaction;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.febrie.backend.article.search.application.ArticleIndexReader indexReader;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper indexMapper;
    @MockitoSpyBean ArticleRepository articles;
    @MockitoSpyBean HikariDataSource dataSource;
    @MockitoBean UploadStorage storage;
    private UserActor actor;
    private final List<Long> createdTags = new ArrayList<>();

    @DynamicPropertySource static void dedicatedDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("IT_DB_URL", "jdbc:mysql://127.0.0.1:3306/easy_community_article_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("文章测试仅允许专用测试库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("app.auth.redis-prefix", () -> PREFIX);
    }

    @BeforeEach void createActor() {
        long owner = Objects.requireNonNull(transaction.execute(status -> accounts.insert(
                "ar_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), Role.USER, "{noop}unused")));
        actor = new UserActor(owner, Role.USER, 1);
    }
    @AfterEach void cleanup() {
        reset(articles);
        reset(dataSource);
        reset(indexMapper);
        articleFixtures.deleteTagsForAuthor(actor.userId());
        articleFixtures.deleteLikeStatesForAuthor(actor.userId()); articleFixtures.deleteLikeCountsForAuthor(actor.userId()); articleFixtures.deleteArticlesForAuthor(actor.userId());
        fixtures.deleteOwnedReferences(actor.userId());
        fixtures.deleteOwnedFiles(actor.userId());
        fixtures.deleteIdentities(actor.userId()); fixtures.deleteUser(actor.userId());
        createdTags.forEach(articleFixtures::deleteTag);
    }
    @Test void indexVersionAndOutboxShareTransaction() {
        long id = commits.create(actor, content("v1", null), new ArticleCommitService.Attempt());
        assertEquals(1L, jdbc.queryForObject("SELECT meta_version FROM article WHERE id=?", Long.class, id));
        commits.update(actor, id, content("v2", null), new ArticleCommitService.Attempt());
        commits.hide(actor, id, new ArticleCommitService.Attempt());
        commits.hide(actor, id, new ArticleCommitService.Attempt());
        assertEquals(List.of(1L, 2L, 3L), jdbc.queryForList(
                "SELECT meta_version FROM article_outbox WHERE article_id=? ORDER BY id", Long.class, id));
        assertEquals(3L, jdbc.queryForObject("SELECT meta_version FROM article WHERE id=?", Long.class, id));
    }

    @Test void indexReaderKeepsMetadataAndTagsInSameSnapshot() throws Exception {
        long tag = tagRepository.insert("idx_" + UUID.randomUUID().toString().substring(0, 16));
        createdTags.add(tag);
        long id = commits.create(actor, content("old", null), new ArticleCommitService.Attempt());
        CountDownLatch articleRead = new CountDownLatch(1), continueTags = new CountDownLatch(1);
        doAnswer(call -> {
            articleRead.countDown();
            if (!continueTags.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("索引标签屏障超时"); }
            // MyBatis接口代理不能callRealMethod，直接取得同一事务中的真实Mapper执行SQL。
            return sqlSession.getMapper(com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper.class).tags(List.of(id));
        }).when(indexMapper).tags(List.of(id));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var read = executor.submit(() -> indexReader.load(id));
            try {
                assertTrue(articleRead.await(5, TimeUnit.SECONDS));
                commits.update(actor, id, new PreparedArticle("new", "new", "{}", "new", null, Set.of(), List.of(tag)), new ArticleCommitService.Attempt());
            } finally { continueTags.countDown(); }
            var snapshot = read.get(10, TimeUnit.SECONDS);
            assertEquals(1, snapshot.metaVersion()); assertEquals("old", snapshot.title()); assertTrue(snapshot.tagIds().isEmpty());
        } finally { continueTags.countDown(); }
        assertEquals(2, indexReader.load(id).metaVersion());
    }

    @Test void outboxFailureRollsBackContentAndVersion() {
        long id = commits.create(actor, content("before", null), new ArticleCommitService.Attempt());
        doThrow(new IllegalStateException("模拟Outbox写入失败")).when(articles).recordIndexEvent(id, true);
        assertThrows(IllegalStateException.class, () -> commits.update(actor, id, content("after", null), new ArticleCommitService.Attempt()));
        assertEquals("before", articles.find(id).orElseThrow().title());
        assertEquals(1L, jdbc.queryForObject("SELECT meta_version FROM article WHERE id=?", Long.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM article_outbox WHERE article_id=?", Integer.class, id));
    }

    @Test void autoIdAndMediaCommitTogetherAndHidePreservesReferences() {
        assertTrue(AopUtils.isAopProxy(commits));
        String image = readyImage();
        long id = commits.create(actor, content("first", image), new ArticleCommitService.Attempt());
        assertTrue(id > 0);
        assertEquals(Set.of(image), files.references(ReferenceType.ARTICLE_IMAGE, id));
        assertEquals(image, articles.find(id).orElseThrow().coverMediaId());
        commits.hide(actor, id, new ArticleCommitService.Attempt());
        assertTrue(articles.find(id).orElseThrow().hidden());
        assertEquals(Set.of(image), files.references(ReferenceType.ARTICLE_IMAGE, id));
        assertEquals(0, articles.countPublic(actor.userId()));
        assertThrows(ApiException.class, () -> commits.update(actor, id, content("late", null), new ArticleCommitService.Attempt()));
        commits.hide(actor, id, new ArticleCommitService.Attempt());
        assertThrows(ApiException.class, () -> commits.hide(new UserActor(actor.userId() + 100, Role.USER, 1), id, new ArticleCommitService.Attempt()));
    }
    @Test void failureAfterBindingRollsBackArticleAndReferences() {
        String image = readyImage();
        doThrow(new IllegalStateException("注入标签写入故障")).when(articles).replaceTags(anyLong(), anyCollection());
        assertThrows(IllegalStateException.class, () -> commits.create(actor, content("x", image), new ArticleCommitService.Attempt()));
        assertEquals(0, articles.countPublic(actor.userId()));
        assertEquals(0, fixtures.countReferences(MediaIds.bytes(image)));
    }
    @Test void updateClearsCoverAndUnreferencedMediaGetsGracePeriod() {
        String image = readyImage();
        long id = commits.create(actor, content("first", image), new ArticleCommitService.Attempt());
        Instant published = articles.find(id).orElseThrow().publishedAt();
        commits.update(actor, id, content("second", null), new ArticleCommitService.Attempt());
        var current = articles.find(id).orElseThrow();
        assertNull(current.coverMediaId());
        assertEquals(published, current.publishedAt());
        assertTrue(files.references(ReferenceType.ARTICLE_IMAGE, id).isEmpty());
        assertTrue(files.find(image).orElseThrow().nextActionAt().isAfter(Instant.now()));
        commits.update(actor, id, content("second", null), new ArticleCommitService.Attempt());
    }
    @Test void rowLockSerializesFullUpdatesAndHideWinsAfterUpdate() throws Exception {
        long id = commits.create(actor, content("initial", null), new ArticleCommitService.Attempt());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (Thread.currentThread().getName().equals("first-article-writer")) {
                locked.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("释放文章锁超时"); }
            }
            return result;
        }).when(articles).lock(id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                Thread.currentThread().setName("first-article-writer");
                return commits.update(actor, id, content("first", null), new ArticleCommitService.Attempt());
            });
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> { secondStarted.countDown(); return commits.update(actor, id, content("last", null), new ArticleCommitService.Attempt()); });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            release.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertEquals("last", articles.find(id).orElseThrow().title());
        } finally { release.countDown(); }
        commits.hide(actor, id, new ArticleCommitService.Attempt());
        assertTrue(articles.find(id).orElseThrow().hidden());
    }

    @Test void readAssemblyKeepsOneRepeatableReadSnapshot() throws Exception {
        long oldTag = tagRepository.insert("old_" + UUID.randomUUID().toString().substring(0, 16));
        long newTag = tagRepository.insert("new_" + UUID.randomUUID().toString().substring(0, 16));
        createdTags.add(oldTag); createdTags.add(newTag);
        var oldContent = new PreparedArticle("old", "old", "{}", "old", null, Set.of(), List.of(oldTag));
        var newContent = new PreparedArticle("new", "new", "{}", "new", null, Set.of(), List.of(newTag));
        long id = commits.create(actor, oldContent, new ArticleCommitService.Attempt());
        CountDownLatch articleRead = new CountDownLatch(1), continueTags = new CountDownLatch(1);
        doAnswer(call -> {
            articleRead.countDown();
            if (!continueTags.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("标签读取屏障超时"); }
            return call.callRealMethod();
        }).when(articles).tags(List.of(id));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var read = executor.submit(() -> reader.detail(id));
            try {
                assertTrue(articleRead.await(5, TimeUnit.SECONDS));
                commits.update(actor, id, newContent, new ArticleCommitService.Attempt());
            } finally { continueTags.countDown(); }
            var dto = read.get(10, TimeUnit.SECONDS).data();
            assertEquals("old", dto.title());
            assertEquals(Long.toString(oldTag), dto.tags().getFirst().tagId());
        } finally { continueTags.countDown(); }
        assertEquals("new", articles.find(id).orElseThrow().title());
    }

    @Test void lostCommitReplyIsUnknownWhetherServerCommittedOrNotAndNeverReplays() throws Exception {
        for (boolean serverCommitted : List.of(false, true)) {
            long before = articles.countPublic(actor.userId());
            AtomicBoolean armed = new AtomicBoolean(true);
            doAnswer(call -> {
                Connection connection = (Connection) call.callRealMethod();
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit") && armed.compareAndSet(true, false)) {
                        if (serverCommitted) { connection.commit(); }
                        throw new SQLNonTransientConnectionException("注入COMMIT回执丢失", "08006");
                    }
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }).when(dataSource).getConnection();
            clearInvocations(articles);
            var command = new SaveArticleCommand(null, "uncertain", mapper.readTree("{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"body\"}]}]}}"), null, List.of());
            assertEquals("ARTICLE_WRITE_RESULT_UNKNOWN", assertThrows(ApiException.class, () -> service.save(actor, command)).code());
            verify(articles, times(1)).insert(eq(actor.userId()), any(), any());
            verify(articles, never()).find(anyLong());
            verify(articles, never()).pageIds(anyLong(), anyLong(), anyInt());
            reset(dataSource);
            assertEquals(before + (serverCommitted ? 1 : 0), articles.countPublic(actor.userId()));
        }
    }

    @Test void deletingFileRejectsNewBindingAndRollsBackNewArticle() {
        String image = readyImage();
        files.deleting(image, Instant.now());
        assertThrows(ApiException.class, () -> commits.create(actor, content("claimed", image), new ArticleCommitService.Attempt()));
        assertEquals(0, articles.countPublic(actor.userId()));
        assertEquals(0, fixtures.countReferences(MediaIds.bytes(image)));
    }

    @Test void bothDeleteUpdateOrdersSerializeOnArticleRow() throws Exception {
        for (boolean hideFirst : List.of(false, true)) {
            long id = commits.create(actor, content("initial", null), new ArticleCommitService.Attempt());
            CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
            doAnswer(call -> {
                var value = call.callRealMethod();
                if (Thread.currentThread().getName().equals("first-delete-update")) {
                    locked.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("删写锁屏障超时"); }
                }
                return value;
            }).when(articles).lock(id);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> {
                    Thread.currentThread().setName("first-delete-update");
                    if (hideFirst) { commits.hide(actor, id, new ArticleCommitService.Attempt()); }
                    else { commits.update(actor, id, content("updated", null), new ArticleCommitService.Attempt()); }
                });
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    if (hideFirst) { commits.update(actor, id, content("late", null), new ArticleCommitService.Attempt()); }
                    else { commits.hide(actor, id, new ArticleCommitService.Attempt()); }
                });
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS)); release.countDown(); first.get(10, TimeUnit.SECONDS);
                if (hideFirst) {
                    var rejected = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
                    assertEquals("ARTICLE_HIDDEN", ((ApiException) rejected.getCause()).code());
                } else { second.get(10, TimeUnit.SECONDS); }
            } finally { release.countDown(); }
            assertTrue(articles.find(id).orElseThrow().hidden());
        }
    }

    @Test void realLockTimeoutRollsBackWholeWriteWithoutSecondAttempt() throws Exception {
        long id = commits.create(actor, content("before", null), new ArticleCommitService.Attempt());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var rc = new TransactionTemplate(transaction.getTransactionManager());
        rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> rc.executeWithoutResult(status -> {
                articles.lock(id); locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("等待释放锁超时"); } }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS)); clearInvocations(articles);
                assertThrows(org.springframework.dao.DataAccessException.class, () -> rc.executeWithoutResult(status -> {
                    articleFixtures.lockWaitSeconds(1);
                    try { commits.update(actor, id, content("must rollback", null), new ArticleCommitService.Attempt()); }
                    finally { articleFixtures.lockWaitSeconds(50); }
                }));
                verify(articles, times(1)).lock(id);
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertEquals("before", articles.find(id).orElseThrow().title());
    }

    @Test void realDeadlockHasNoPartialArticleOrMediaCommit() throws Exception {
        String image = readyImage();
        long id = commits.create(actor, content("before", null), new ArticleCommitService.Attempt());
        CountDownLatch fileLocked = new CountDownLatch(1), articleLocked = new CountDownLatch(1);
        var rc = new TransactionTemplate(transaction.getTransactionManager());
        rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        doAnswer(call -> {
            var row = call.callRealMethod();
            if (Thread.currentThread().getName().equals("deadlock-article-writer")) { articleLocked.countDown(); }
            return row;
        }).when(articles).lock(id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            // 测试故意逆序持锁制造死锁；生产代码始终使用文章→文件顺序。
            var holder = executor.submit(() -> {
                try {
                    rc.executeWithoutResult(status -> {
                        files.lock(image); fileLocked.countDown();
                        try { if (!articleLocked.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("文章锁屏障超时"); } }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                        articles.lock(id);
                    });
                    return (RuntimeException) null;
                } catch (RuntimeException error) { return error; }
            });
            assertTrue(fileLocked.await(5, TimeUnit.SECONDS));
            var writer = executor.submit(() -> {
                Thread.currentThread().setName("deadlock-article-writer");
                try { commits.update(actor, id, content("after", image), new ArticleCommitService.Attempt()); return (RuntimeException) null; }
                catch (RuntimeException error) { return error; }
            });
            RuntimeException holderError = holder.get(10, TimeUnit.SECONDS), writerError = writer.get(10, TimeUnit.SECONDS);
            Throwable victim = holderError != null ? holderError : writerError;
            assertNotNull(victim);
            boolean deadlock = false;
            for (Throwable cause = victim; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1213) { deadlock = true; }
            }
            assertTrue(deadlock, "必须触发真实MySQL死锁");
            assertEquals(writerError == null ? "after" : "before", articles.find(id).orElseThrow().title());
            assertEquals(writerError == null ? Set.of(image) : Set.of(), files.references(ReferenceType.ARTICLE_IMAGE, id));
        }
    }

    @Test void processExitAfterCommitLeavesOldCacheUntilFixedTtlExpires() throws Exception {
        long id = commits.create(actor, content("before", null), new ArticleCommitService.Attempt());
        assertEquals("before", queries.detail(id).title());
        assertEquals("before", queries.page(actor, 1, 20).items().getFirst().title());
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(javaExecutable))) {
            javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var output = java.nio.file.Path.of("target", "article-crash-child-" + id + ".log").toFile();
        List<String> command = new ArrayList<>(List.of(javaExecutable));
        // 继承调用者本地JVM参数，不把Windows临时目录写死为跨平台测试要求。
        String socketDirectory = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (socketDirectory != null) { command.add("-Djdk.net.unixdomain.tmpdir=" + socketDirectory); }
        command.addAll(List.of("-cp", classpath, ArticleCrashProcess.class.getName(), Long.toString(actor.userId()), Long.toString(id), PREFIX));
        var child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
        child.environment().put("IT_DB_URL", dataSource.getJdbcUrl());
        Process process = child.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "崩溃子进程未在预算内退出");
            assertEquals(0, process.exitValue(), "检查测试子进程日志：" + output);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        assertEquals("after-crash", reader.edit(actor, id).title());
        assertEquals("before", queries.detail(id).title());
        assertEquals("before", queries.page(actor, 1, 20).items().getFirst().title());
        // 缩短本次测试Key的剩余TTL，再等待实际过期；生产固定TTL范围由Redis测试单独断言。
        redis.expire(cache.key(id, "detail"), java.time.Duration.ofMillis(50));
        redis.expire(cache.key(id, "list"), java.time.Duration.ofMillis(50));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                !Boolean.TRUE.equals(redis.hasKey(cache.key(id, "detail"))) && !Boolean.TRUE.equals(redis.hasKey(cache.key(id, "list"))));
        assertEquals("after-crash", queries.detail(id).title());
        assertEquals("after-crash", queries.page(actor, 1, 20).items().getFirst().title());
        for (String suffix : List.of("detail", "list", "cache-lock", "hidden")) { redis.delete(cache.key(id, suffix)); }
    }

    private String readyImage() {
        String id = UUID.randomUUID().toString();
        files.insert(new FileRecord(id, actor.userId(), MediaPurpose.ARTICLE_IMAGE, MediaStatus.ISSUED, null, null,
                Instant.now(), Instant.now().plusSeconds(7200)));
        fixtures.makeReady(MediaIds.bytes(id), 100);
        return id;
    }
    private static PreparedArticle content(String title, String image) {
        return new PreparedArticle(title, title, "{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\"}}", "<p>" + title + "</p>",
                image, image == null ? Set.of() : Set.of(image), List.of());
    }
}
