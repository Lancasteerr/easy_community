package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.*;
import com.febrie.backend.article.infrastructure.persistence.ArticleRepository;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式启用的真实MySQL事务测试；屏障控制时序，不靠随机sleep证明并发正确。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class ArticleMysqlIntegrationTests {
    @Autowired ArticleCommitService commits;
    @Autowired AccountRepository accounts;
    @Autowired FileRepository files;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired ArticleFixtureMapper articleFixtures;
    @Autowired TransactionTemplate transaction;
    @MockitoSpyBean ArticleRepository articles;
    @MockitoBean UploadStorage storage;
    private UserActor actor;

    @DynamicPropertySource static void dedicatedDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("IT_DB_URL", "jdbc:mysql://127.0.0.1:3306/easy_community_article_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("文章测试仅允许专用测试库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("app.auth.redis-prefix", () -> "ec:test:article:" + UUID.randomUUID());
    }

    @BeforeEach void createActor() {
        long owner = Objects.requireNonNull(transaction.execute(status -> accounts.insert(
                "ar_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), Role.USER, "{noop}unused")));
        actor = new UserActor(owner, Role.USER, 1);
    }
    @AfterEach void cleanup() {
        reset(articles);
        articleFixtures.deleteTagsForAuthor(actor.userId());
        articleFixtures.deleteArticlesForAuthor(actor.userId());
        fixtures.deleteOwnedReferences(actor.userId());
        fixtures.deleteOwnedFiles(actor.userId());
        fixtures.deleteIdentities(actor.userId()); fixtures.deleteUser(actor.userId());
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
