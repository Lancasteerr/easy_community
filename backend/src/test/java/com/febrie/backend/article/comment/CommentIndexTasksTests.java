package com.febrie.backend.article.comment;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 新点赞抢先推进版本时，只能释放旧租约。 */
class CommentIndexTasksTests {
    @Test
    void concurrentChangeKeepsWorkAfterAnOlderSuccessfulBulk() {
        var sql = mock(JdbcTemplate.class);
        var tasks = new CommentIndexTasks(sql);
        var claimed = new CommentIndexTasks.Event(7, 7, 8);
        tasks.finishLike(claimed, "lease", true);
        var order = inOrder(sql);
        order.verify(sql).update(contains("claim_token = ? AND index_version = ?"), eq(7L), eq("lease"), eq(8L));
        order.verify(sql).update(contains("SET claim_token = NULL"), eq(0), eq(7L), eq("lease"));
        verifyNoMoreInteractions(sql);
    }
}
