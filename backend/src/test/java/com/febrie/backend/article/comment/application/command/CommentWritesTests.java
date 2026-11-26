package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCountOutbox;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.infrastructure.redis.CommentMaintenance;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 检查字符边界、跨文章归属和提交后处理顺序。 */
class CommentWritesTests {
    private final CommentStore store = mock(CommentStore.class);
    private final CommentMaintenance maintenance = mock(CommentMaintenance.class);
    private final CommentWrites writes = new CommentWrites(store, maintenance, mock(CommentCountOutbox.class), Clock.systemUTC());
    private final CommentWriteService service = new CommentWriteService(
            writes, mock(CommentSubmissionTransactions.class), mock(CommentCommandResults.class), maintenance);
    private final UserActor author = new UserActor(2, Role.USER, 1);

    @Test
    void validatesUnicodeCodePoints() {
        assertEquals("🙂".repeat(300), CommentWrites.content("  " + "🙂".repeat(300) + " "));
        assertThrows(ApiException.class, () -> CommentWrites.content("🙂".repeat(301)));
        assertThrows(ApiException.class, () -> CommentWrites.content(" \t "));
        assertThrows(ApiException.class, () -> CommentWrites.content("\uD800"));
    }

    @Test
    void firstDeleteWritesOnceAndPublishesOnlyAfterCommit() {
        var active = row(1);
        var deleted = new CommentRow(3, 1, 7, 7L, 2, null, Instant.EPOCH, true);
        when(store.find(3, false)).thenReturn(active, deleted);
        when(store.isPublicArticle(1)).thenReturn(true);
        when(store.deleteIfActive(active)).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(1, 3, author, null);
            service.delete(1, 3, author, null);
            // 每次只读取一次目标和文章，不查询楼层，也不执行加锁查询。
            verify(store, times(2)).find(3, false);
            verify(store, times(2)).isPublicArticle(1);
            verify(store).deleteIfActive(active);
            verifyNoMoreInteractions(store);
            verifyNoInteractions(maintenance);
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.forEach(TransactionSynchronization::afterCommit);
            verify(maintenance).visibility(active);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void concurrentDeleteSucceedsWithoutRegisteringInvalidation() {
        var active = row(1);
        when(store.find(3, false)).thenReturn(active);
        when(store.isPublicArticle(1)).thenReturn(true);
        // 查询时回复仍有效，但在条件更新前已被另一个请求删除。
        when(store.deleteIfActive(active)).thenReturn(false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(1, 3, author, null);
            verify(store).deleteIfActive(active);
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
            verifyNoInteractions(maintenance);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void hiddenArticleRejectsReplyDeletion() {
        when(store.find(3, false)).thenReturn(row(1));
        when(store.isPublicArticle(1)).thenReturn(false);
        assertEquals("COMMENT_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.delete(1, 3, author, null)).code());
        verify(store, never()).deleteIfActive(any());
        verifyNoInteractions(maintenance);
    }

    @Test
    void createReplyRejectsForeignTarget() {
        when(store.lockPublicArticle(1, false)).thenReturn(true);
        // 主库查询未找到属于当前文章的可见目标。
        when(store.lockReplyTargetAndFindRoot(1, 3)).thenReturn(null);
        assertEquals("COMMENT_NOT_FOUND", assertThrows(ApiException.class,
                () -> writes.insertReply(1, author, 3L, "正文")).code());
        verify(store).lockReplyTargetAndFindRoot(1, 3);
        verify(store, never()).insert(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void deleteReplyRejectsForeignTargetAndUnauthorizedActor() {
        when(store.find(3, false)).thenReturn(row(2), row(1));
        assertEquals("COMMENT_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.delete(1, 3, author, null)).code());
        var otherUser = new UserActor(9, Role.USER, 1);
        assertEquals("COMMENT_FORBIDDEN", assertThrows(ApiException.class,
                () -> service.delete(1, 3, otherUser, null)).code());
        verify(store, never()).deleteIfActive(any());
        verifyNoInteractions(maintenance);
    }

    private static CommentRow row(long article) {
        return new CommentRow(3, article, 7, 7L, 2, "正文", Instant.EPOCH, false);
    }
}
