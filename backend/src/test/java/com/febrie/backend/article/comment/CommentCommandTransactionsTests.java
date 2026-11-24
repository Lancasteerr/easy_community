package com.febrie.backend.article.comment;

import com.febrie.backend.user.application.AccountQueryService;
import com.febrie.backend.user.application.dto.AccountAuthState;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只测试批次内的幂等与净计数，不启动数据库或队列。 */
class CommentCommandTransactionsTests {
    @Test
    void distinctCreatesShareOneCounterUpdateAndReplayDoesNotApplyAgain() {
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var store = mock(CommentStore.class);
        var results = mock(CommentCommandResults.class);
        var accounts = mock(AccountQueryService.class);
        var actor = new UserActor(2, Role.USER, 1);
        var first = new CommentCommand(1, actor, UUID.randomUUID().toString(), null, "第一条");
        var second = new CommentCommand(1, actor, UUID.randomUUID().toString(), null, "第二条");
        when(store.lockPublicArticle(1, true)).thenReturn(true);
        when(accounts.authState(2)).thenReturn(Optional.of(new AccountAuthState("ACTIVE", 1)));
        when(results.find(1, 2, first.operationId())).thenReturn(null,
                new CommentCommandResults.Result(first.fingerprint(), 8L, null));
        when(store.insert(1, 0, null, 2, first.content(), clock.instant())).thenReturn(
                new CommentRow(8, 1, 0, null, 2, first.content(), clock.instant(), false));
        when(store.insert(1, 0, null, 2, second.content(), clock.instant())).thenReturn(
                new CommentRow(9, 1, 0, null, 2, second.content(), clock.instant(), false));

        var service = new CommentCommandTransactions(store, results, accounts, clock);
        assertEquals(2, service.apply(1, List.of(first, second, first)).delta());
        verify(store).changeCount(1, 2);
        verify(store).appendIndex(8);
        verify(store).appendIndex(9);
        verify(results).insert(first, 8L, null);
    }
}
