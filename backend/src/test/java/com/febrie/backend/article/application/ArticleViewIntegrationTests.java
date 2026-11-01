package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.PreparedArticle;
import com.febrie.backend.article.infrastructure.cache.*;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.support.PersistenceTestConfiguration;
import com.febrie.backend.support.persistence.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MySQL与Redis贯通计数、装配、刷盘和SQL事务边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PersistenceTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class ArticleViewIntegrationTests {
    private static final String PREFIX = "ec:test:views:" + UUID.randomUUID();
    @Autowired ArticleQueryService queries;
    @Autowired ArticleViewReader reader;
    @Autowired ArticleViewSqlService sql;
    @Autowired ArticleViewFlush flush;
    @Autowired ArticleCommitService commits;
    @Autowired AccountRepository accounts;
    @Autowired ArticleFixtureMapper articleFixtures;
    @Autowired DatabaseFixtureMapper fixtures;
    @Autowired TransactionTemplate transaction;
    @Autowired StringRedisTemplate redis;
    @Autowired ArticleCache cache;
    @Autowired Clock clock;
    @MockitoSpyBean ArticleViewStore store;
    @MockitoBean UploadStorage storage;
    private UserActor actor;
    private long id;

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("IT_DB_URL", "jdbc:mysql://127.0.0.1:3306/easy_community_article_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if (!url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("浏览量测试仅允许专用测试库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("app.auth.redis-prefix", () -> PREFIX);
    }

    @BeforeEach void create() {
        long owner = Objects.requireNonNull(transaction.execute(status -> accounts.insert(
                "vi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), Role.USER, "{noop}unused")));
        actor = new UserActor(owner, Role.USER, 1);
        id = commits.create(actor, new PreparedArticle("title", "summary", "{}", "<p>text</p>", null, Set.of(), List.of()),
                new ArticleCommitService.Attempt());
    }

    @AfterEach void clean() {
        reset(store);
        articleFixtures.deleteArticlesForAuthor(actor.userId());
        fixtures.deleteIdentities(actor.userId()); fixtures.deleteUser(actor.userId());
        for (String pattern : List.of(PREFIX + ":*", "{" + PREFIX + ":article:stats:v1}:*")) {
            try (var keys = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
                while (keys.hasNext()) { redis.delete(keys.next()); }
            }
        }
    }

    @Test void realtimeDetailsListsAndFlushKeepRedisOutsideSqlTransactions() {
        org.mockito.stubbing.Answer<Object> outsideSql = call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Redis不能位于SQL事务内");
            return call.callRealMethod();
        };
        doAnswer(outsideSql).when(store).increment(anyLong(), any(), any());
        doAnswer(outsideSql).when(store).totals(anyCollection());
        doAnswer(outsideSql).when(store).initialize(anyMap());
        doAnswer(outsideSql).when(store).daily(any());
        doAnswer(outsideSql).when(store).repair(any(), any());
        // 列表首次读取初始化SQL零基线，不创建日PV；详情随后连续两次增加。
        assertEquals(0L, queries.page(actor, 1, 20).items().getFirst().viewCount());
        assertEquals(1L, queries.detail(id).viewCount());
        assertEquals(2L, queries.detail(id).viewCount());
        assertEquals(2L, queries.page(actor, 1, 20).items().getFirst().viewCount());
        queries.edit(actor, id);
        assertEquals(0, reader.total(id));
        assertFalse(Objects.requireNonNull(redis.opsForValue().get(cache.key(id, "detail"))).contains("viewCount"));
        assertFalse(Objects.requireNonNull(redis.opsForValue().get(cache.key(id, "list"))).contains("viewCount"));
        flush.flush();
        assertEquals(2, reader.total(id));
        flush.flush();
        assertEquals(2, reader.total(id));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ArticleViewService.BUSINESS_ZONE);
        assertEquals(Map.of(id, 2L), store.daily(today));
        assertEquals(Map.of(id, 2L), store.totals(List.of(id)));
    }

    @Test void sqlBaselineRecoversMissingTotalAndStatsFailureFallsBackWithoutRetry() {
        sql.flush(LocalDate.of(2026, 11, 1), Map.of(id, 10L));
        assertEquals(11L, queries.detail(id).viewCount());
        redis.opsForHash().delete(store.totalKey(), Long.toString(id));
        assertEquals(11L, queries.detail(id).viewCount());
        clearInvocations(store);
        doThrow(new IllegalStateException("计数执行结果未知")).when(store).increment(eq(id), any(), any());
        assertEquals(10L, queries.detail(id).viewCount());
        verify(store, times(1)).increment(eq(id), any(), any());
        doThrow(new IllegalStateException("总量读取失败")).when(store).totals(anyCollection());
        assertEquals(10L, queries.page(actor, 1, 20).items().getFirst().viewCount());
    }

    @Test void transactionProxyRejectsRedisInsideSqlAndHiddenDetailDoesNotCount() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> transaction.execute(status -> store.totals(List.of(id))));
        commits.hide(actor, id, new ArticleCommitService.Attempt());
        assertThrows(com.febrie.backend.shared.error.ApiException.class, () -> queries.detail(id));
        verify(store, never()).increment(anyLong(), any(), any());
    }
}
