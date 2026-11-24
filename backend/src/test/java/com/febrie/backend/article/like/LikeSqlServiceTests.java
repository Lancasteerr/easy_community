package com.febrie.backend.article.like;

import com.febrie.backend.article.comment.CommentIndexTasks;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.persistence.LikeRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 事务原子性另由MySQL集成测试验证；此处锁定版本裁决与净增量规则。 */
class LikeSqlServiceTests {
    private final LikeRepository repository = mock(LikeRepository.class);
    private final LikeSqlService service = new LikeSqlService(repository, LikeTestProperties.defaults(), mock(CommentIndexTasks.class));

    @org.junit.jupiter.api.BeforeEach void counterBaseline() { when(repository.lockCount(2)).thenReturn(1L); }

    @Test void sameStateHigherVersionStillAdvancesWithoutCounterWrite() {
        when(repository.lock(1, 2)).thenReturn(new LikeState(1, 2, true, 10));
        when(repository.update(new LikeState(1, 2, true, 12))).thenReturn(1);
        assertEquals(LikeSqlService.Status.APPLIED, service.apply(command(true, 12)).status());
        verify(repository).update(new LikeState(1, 2, true, 12));
        verify(repository, never()).addDelta(anyLong(), anyInt());
    }

    @Test void staleReturnsActualSqlStateAndConflictingDuplicateIsRejected() {
        var actual = new LikeState(1, 2, false, 12);
        when(repository.lock(1, 2)).thenReturn(actual);
        assertEquals(actual, service.apply(command(true, 11)).state());
        assertThrows(LikeInvariantException.class, () -> service.apply(command(true, 12)));
        assertEquals(LikeSqlService.Status.DUPLICATE, service.apply(command(false, 12)).status());
        verify(repository, never()).update(any());
    }

    @Test void counterFailureThrowsInsteadOfSilentlyAcceptingPartialState() {
        when(repository.lock(1, 2)).thenReturn(new LikeState(1, 2, false, 0));
        when(repository.update(any())).thenReturn(1);
        when(repository.addDelta(2, 1)).thenReturn(0);
        assertThrows(LikeInvariantException.class, () -> service.apply(command(true, 10)));
    }

    @Test void packedVersionOrderingAndMalformedMessages() {
        assertTrue(LikeVersion.pack(6, 1) > LikeVersion.pack(5, LikeVersion.SEQUENCE_LIMIT - 1));
        assertThrows(IllegalArgumentException.class, () -> LikeVersion.pack(1, 0));
        assertThrows(IllegalArgumentException.class, () -> LikeVersion.parse("9007199254740992"));
        assertThrows(IllegalArgumentException.class, () -> LikeCommand.operation("1-1-1-1-1"));
        assertEquals(19, LikeVersion.cache(Long.MAX_VALUE).length());
    }

    @Test void mixedRelationsUseOneNetCounterWriteAndStableLockOrder() {
        when(repository.lock(1, 2)).thenReturn(new LikeState(1, 2, true, 10));
        when(repository.lock(2, 2)).thenReturn(new LikeState(2, 2, false, 0));
        when(repository.lock(3, 2)).thenReturn(new LikeState(3, 2, false, 0));
        when(repository.update(any())).thenReturn(1);
        when(repository.addDelta(2, 1)).thenReturn(1);
        var result = service.applyArticle(2, java.util.List.of(LikeBatchReducerTests.command(3, true, 12),
                command(false, 12), LikeBatchReducerTests.command(2, true, 12)));
        assertEquals(1, result.delta());
        verify(repository, times(1)).addDelta(2, 1);
        var order = inOrder(repository);
        order.verify(repository).initializeState(1, 2); order.verify(repository).lock(1, 2);
        order.verify(repository).update(new LikeState(1, 2, false, 12));
        order.verify(repository).initializeState(2, 2); order.verify(repository).lock(2, 2);
        order.verify(repository).update(new LikeState(2, 2, true, 12));
        order.verify(repository).initializeState(3, 2); order.verify(repository).lock(3, 2);
    }

    @Test void zeroDeltaStillAdvancesVersionsAndMissingBaselineIsRejected() {
        when(repository.lock(1, 2)).thenReturn(new LikeState(1, 2, true, 10));
        when(repository.lock(2, 2)).thenReturn(new LikeState(2, 2, false, 0));
        when(repository.update(any())).thenReturn(1);
        var commands = java.util.List.of(command(false, 12), LikeBatchReducerTests.command(2, true, 12));
        assertEquals(0, service.applyArticle(2, commands).delta());
        verify(repository, never()).addDelta(anyLong(), anyInt());
        when(repository.lockCount(2)).thenReturn(null);
        assertThrows(LikeInvariantException.class, () -> service.applyArticle(2, commands));
    }

    @Test void invalidTransactionBoundariesFailBeforeTakingLocks() {
        assertThrows(IllegalArgumentException.class, () -> service.applyArticle(2, java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.applyArticle(2, java.util.Collections.nCopies(21, command(true, 10))));
        assertThrows(IllegalArgumentException.class, () -> service.applyArticle(2, java.util.List.of(command(true, 10), command(false, 11))));
        assertThrows(IllegalArgumentException.class, () -> service.applyArticle(3, java.util.List.of(command(true, 10))));
        verify(repository, never()).initializeState(anyLong(), anyLong());
    }

    static LikeCommand command(boolean liked, long version) {
        return new LikeCommand(1, UUID.randomUUID().toString(), 1L, 2L, liked, Long.toString(version), Instant.now());
    }
}
