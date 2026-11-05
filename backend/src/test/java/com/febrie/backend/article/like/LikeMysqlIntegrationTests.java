package com.febrie.backend.article.like;

import com.febrie.backend.article.application.dto.PreparedArticle;
import com.febrie.backend.article.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.LikeRepository;
import com.febrie.backend.media.application.UploadStorage;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 独立测试库上的真实事务/行锁；不依赖Redis或MQ，不清业务库。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "IT_LIKE", matches = "true")
class LikeMysqlIntegrationTests {
    @Autowired LikeSqlService service;
    @Autowired LikeRepository likes;
    @Autowired ArticleRepository articles;
    @Autowired AccountRepository accounts;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean UploadStorage storage;
    long user, article;

    @DynamicPropertySource static void dedicated(DynamicPropertyRegistry registry) {
        String url = System.getenv("IT_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) { throw new IllegalArgumentException("仅允许专用点赞测试库"); }
        registry.add("spring.datasource.url", () -> url);
    }

    @BeforeEach void seed() {
        user = transaction.execute(s -> accounts.insert("lk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), Role.USER, "test"));
        article = transaction.execute(s -> articles.insert(user,
                new PreparedArticle("like", "like", "{}", "<p>like</p>", null, Set.of(), List.of()), Instant.now()));
    }
    @AfterEach void clean() {
        jdbc.update("DELETE FROM article_like_state WHERE article_id=?", article);
        jdbc.update("DELETE FROM article_like_count WHERE article_id=?", article);
        jdbc.update("DELETE FROM article WHERE id=?", article);
        jdbc.update("DELETE FROM user_auth_identity WHERE user_id=?", user);
        jdbc.update("DELETE FROM users WHERE id=?", user);
    }

    @Test void highestVersionAndDuplicateKeepExactCounter() {
        service.apply(command(user, true, 10)); service.apply(command(user, true, 12)); service.apply(command(user, false, 11));
        assertEquals(1L, likes.counts(List.of(article)).get(article));
        assertEquals(12, likes.states(user, List.of(article)).get(article).version());
        assertThrows(LikeInvariantException.class, () -> service.apply(command(user, false, 12)));
        service.apply(command(user, true, 12));
        assertEquals(1L, likes.counts(List.of(article)).get(article));
    }

    @Test void cancelTombstoneBlocksOldLikeAndMissingCounterRollsBackState() {
        service.apply(command(user, false, 12)); service.apply(command(user, true, 11));
        assertFalse(likes.states(user, List.of(article)).get(article).liked());
        jdbc.update("DELETE FROM article_like_count WHERE article_id=?", article);
        assertThrows(LikeInvariantException.class, () -> service.apply(command(user, true, 13)));
        assertEquals(12, likes.states(user, List.of(article)).get(article).version());
    }

    @Test void simultaneousWorkersAndHotCounterRemainConsistent() throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.apply(command(user, true, 10)); });
            var second = pool.submit(() -> { start.await(); return service.apply(command(user, false, 11)); });
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(11, likes.states(user, List.of(article)).get(article).version());
        assertEquals(0L, likes.counts(List.of(article)).get(article));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<?>>();
            // 独立关系竞争同一计数行；命令来自内部MQ，不重复读取账号认证状态。
            for (long id = 100000; id < 100020; id++) { long actor = id; tasks.add(pool.submit(() -> service.apply(command(actor, true, 20)))); }
            for (var task : tasks) { task.get(10, TimeUnit.SECONDS); }
        }
        assertEquals(20L, likes.counts(List.of(article)).get(article));
        assertEquals(20L, jdbc.queryForObject("SELECT COUNT(*) FROM article_like_state WHERE article_id=? AND liked=1", Long.class, article));
    }

    private LikeCommand command(long actor, boolean liked, long version) {
        return new LikeCommand(1, UUID.randomUUID().toString(), actor, article, liked, Long.toString(version), Instant.now());
    }
}
