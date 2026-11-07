package com.febrie.backend.article.search;

import com.febrie.backend.article.search.application.IndexOutbox;
import com.febrie.backend.article.search.infrastructure.persistence.mapper.IndexOutboxMapper;
import com.febrie.backend.media.application.UploadStorage;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

/** 真实SQL租约与令牌验证；只清理本用例唯一article_id对应的测试事件。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class IndexOutboxIntegrationTests {
    @Autowired IndexOutbox outbox;
    @Autowired IndexOutboxMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean UploadStorage storage;
    private final long article = 8_000_000_000_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1000000);
    @AfterEach void cleanup() { jdbc.update("DELETE FROM article_outbox WHERE article_id=?", article); }
    @Test void expiredClaimCannotDeleteOrDelayNewOwner() {
        jdbc.update("INSERT INTO article_outbox(article_id,meta_version) VALUES (?,1)", article);
        long id = jdbc.queryForObject("SELECT id FROM article_outbox WHERE article_id=?", Long.class, article);
        var old = outbox.claim();
        assertTrue(old.events().stream().anyMatch(e -> e.eventId() == id));
        jdbc.update("UPDATE article_outbox SET available_at=CURRENT_TIMESTAMP(3) WHERE id=?", id);
        var current = outbox.claim();
        outbox.complete(id, old.token());
        outbox.retry(id, old.token());
        assertArrayEquals(current.token(), jdbc.queryForObject("SELECT claim_token FROM article_outbox WHERE id=?", byte[].class, id));
        outbox.complete(id, current.token());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM article_outbox WHERE id=?", Integer.class, id));
    }
    @Test void independentRelaysCannotOwnSameUnexpiredEvent() throws Exception {
        jdbc.update("INSERT INTO article_outbox(article_id,meta_version) VALUES (?,1)", article);
        long id = jdbc.queryForObject("SELECT id FROM article_outbox WHERE article_id=?", Long.class, article);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<IndexOutbox.Claim> task = () -> { barrier.await(); return outbox.claim(); };
            var a = executor.submit(task); var b = executor.submit(task);
            long owners = java.util.stream.Stream.concat(a.get().events().stream(), b.get().events().stream())
                    .filter(e -> e.eventId() == id).count();
            assertEquals(1, owners);
        }
    }
}
