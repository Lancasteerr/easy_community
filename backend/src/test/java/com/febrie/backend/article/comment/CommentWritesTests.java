package com.febrie.backend.article.comment;

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
    private final CommentWrites writes = new CommentWrites(store, maintenance, Clock.systemUTC());
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
        when(store.lockPublicArticle(1, false)).thenReturn(true);
        when(store.find(3, false)).thenReturn(active);
        when(store.find(7, true)).thenReturn(new CommentRow(7, 1, 0, null, 2, "楼层", Instant.EPOCH, false));
        when(store.find(3, true)).thenReturn(active, new CommentRow(3, 1, 7, 7L, 2, null, Instant.EPOCH, true));
        TransactionSynchronizationManager.initSynchronization();
        try {
            writes.deleteReply(1, 3, author);
            writes.deleteReply(1, 3, author);
            verify(store).delete(active);
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
    void foreignReplyAndUnauthorizedDeletionAreRejected() {
        when(store.lockPublicArticle(1, false)).thenReturn(true);
        when(store.find(3, false)).thenReturn(row(2));
        assertEquals("COMMENT_NOT_FOUND", assertThrows(ApiException.class,
                () -> writes.createReply(1, author, 3L, "正文")).code());
        when(store.lockPublicArticle(1, false)).thenReturn(true);
        when(store.find(3, false)).thenReturn(row(1));
        when(store.find(7, true)).thenReturn(new CommentRow(7, 1, 0, null, 2, "楼层", Instant.EPOCH, false));
        when(store.find(3, true)).thenReturn(row(1));
        var otherUser = new UserActor(9, Role.USER, 1);
        assertEquals("COMMENT_FORBIDDEN", assertThrows(ApiException.class,
                () -> writes.deleteReply(1, 3, otherUser)).code());
        verify(store, never()).delete(any());
    }

    private static CommentRow row(long article) {
        return new CommentRow(3, article, 7, 7L, 2, "正文", Instant.EPOCH, false);
    }
}
