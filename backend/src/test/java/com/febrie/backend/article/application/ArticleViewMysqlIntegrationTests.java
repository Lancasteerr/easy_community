package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.PreparedArticle;
import com.febrie.backend.article.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.article.infrastructure.persistence.mapper.ArticleViewStatMapper;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 专用真实MySQL验证日值与总量的事务一致性，不依赖Redis。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class ArticleViewMysqlIntegrationTests {
    @Autowired ArticleViewSqlService service;
    @Autowired ArticleViewReader reader;
    @Autowired ArticleCommitService commits;
    @Autowired ArticleRepository articles;
    @Autowired AccountRepository accounts;
    @Autowired ArticleFixtureMapper articleFixtures;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired TransactionTemplate transaction;
    @Autowired org.mybatis.spring.SqlSessionTemplate sessions;
    @MockitoSpyBean ArticleViewStatMapper stats;
    @MockitoBean UploadStorage storage;
    private UserActor actor;
    private long articleId;
    private final LocalDate date = LocalDate.of(2026, 11, 2);

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("IT_DB_URL", "jdbc:mysql://127.0.0.1:3306/easy_community_article_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("浏览量测试仅允许专用测试库");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @BeforeEach void create() {
        long owner = Objects.requireNonNull(transaction.execute(status -> accounts.insert(
                "pv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), Role.USER, "{noop}unused")));
        actor = new UserActor(owner, Role.USER, 1);
        articleId = commits.create(actor, content("before"), new ArticleCommitService.Attempt());
    }

    @AfterEach void clean() {
        reset(stats);
        articleFixtures.deleteLikeStatesForAuthor(actor.userId()); articleFixtures.deleteLikeCountsForAuthor(actor.userId()); articleFixtures.deleteArticlesForAuthor(actor.userId());
        fixtures.deleteIdentities(actor.userId());
        fixtures.deleteUser(actor.userId());
    }

    @Test void repeatedAndOlderSnapshotsNeverDoubleCountAndEditingPreservesTotal() {
        var before = articles.find(articleId).orElseThrow();
        service.flush(date, Map.of(articleId, 7L));
        assertEquals(before, articles.find(articleId).orElseThrow());
        service.flush(date, Map.of(articleId, 7L));
        var repair = service.flush(date, Map.of(articleId, 2L));
        assertEquals(Map.of(articleId, 7L), repair.dailyFloors());
        service.flush(date.plusDays(1), Map.of(articleId, 3L));
        commits.update(actor, articleId, content("after"), new ArticleCommitService.Attempt());
        assertEquals(10, reader.total(articleId));
        commits.hide(actor, articleId, new ArticleCommitService.Attempt());
        service.flush(date.plusDays(1), Map.of(articleId, 5L));
        assertEquals(12, reader.total(articleId));
        Long daily = transaction.execute(status -> stats.selectPvForUpdate(articleId, date));
        assertEquals(7L, daily);
    }

    @Test void laterRowFailureRollsBackTheWholeDate() {
        long second = commits.create(actor, content("second"), new ArticleCommitService.Attempt());
        doThrow(new IllegalStateException("注入日统计故障")).when(stats).upsertPv(second, date, 4);
        assertThrows(IllegalStateException.class, () -> service.flush(date, Map.of(articleId, 7L, second, 4L)));
        assertEquals(0, reader.total(articleId));
        assertEquals(0, reader.total(second));
        assertNull(transaction.execute(status -> stats.selectPvForUpdate(articleId, date)));
        reset(stats);
        service.flush(date, Map.of(articleId, 7L, second, 4L));
        assertEquals(7, reader.total(articleId));
        assertEquals(4, reader.total(second));
    }

    @Test void concurrentFirstInsertCanRetryWithoutDoubleCounting() throws Exception {
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> {
            // MyBatis Mapper是接口代理，直接通过同一事务会话执行真实锁定SQL。
            Object value = sessions.selectOne(ArticleViewStatMapper.class.getName() + ".selectPvForUpdate",
                    Map.of("articleId", articleId, "date", date));
            if (value == null) { barrier.await(10, TimeUnit.SECONDS); }
            return value;
        }).when(stats).selectPvForUpdate(articleId, date);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> flush = () -> {
                try { service.flush(date, Map.of(articleId, 7L)); return true; }
                catch (org.springframework.dao.DataAccessException expectedConflict) { return false; }
            };
            var first = executor.submit(flush);
            var second = executor.submit(flush);
            boolean firstSucceeded = first.get(20, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(20, TimeUnit.SECONDS);
            assertTrue(firstSucceeded || secondSucceeded);
        }
        reset(stats);
        service.flush(date, Map.of(articleId, 7L));
        assertEquals(7, reader.total(articleId));
        // 已有日值也必须在锁内读取最新值，不能按事务旧快照重复增加。
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.flush(date, Map.of(articleId, 9L)));
            var second = executor.submit(() -> service.flush(date, Map.of(articleId, 9L)));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(9, reader.total(articleId));
    }

    private PreparedArticle content(String title) {
        return new PreparedArticle(title, title, "{}", title, null, Set.of(), List.of());
    }
}
