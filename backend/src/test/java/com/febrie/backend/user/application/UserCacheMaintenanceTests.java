package com.febrie.backend.user.application;

import com.febrie.backend.user.infrastructure.cache.UserBasicCacheStore;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 回调登记本身不操作Redis，回滚不删除，提交后的异常不能向业务传播。 */
class UserCacheMaintenanceTests {
    private final UserBasicCacheStore cache = mock(UserBasicCacheStore.class);
    private final UserCacheMaintenance maintenance = new UserCacheMaintenance(cache);
    @BeforeEach void begin() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }

    @Test void onlyCommitCompetesForLockAndDeletesWithoutCheckingExistence() {
        when(cache.tryLock(1)).thenReturn("token");
        when(cache.invalidate(1, "token")).thenReturn(true);
        maintenance.invalidateAfterCommit(1);
        verifyNoInteractions(cache);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        var order = inOrder(cache);
        order.verify(cache).tryLock(1);
        order.verify(cache).invalidate(1, "token");
        order.verify(cache).release(1, "token");
        verify(cache, never()).get(anyCollection());
    }

    @Test void rollbackDoesNotTouchCache() {
        maintenance.invalidateAfterCommit(1);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(cache);
    }

    @Test void failedInvalidationIsBoundedAndUnlocksBeforeRetry() {
        when(cache.tryLock(1)).thenReturn("a", "b", "c");
        when(cache.invalidate(eq(1L), anyString())).thenThrow(new IllegalStateException("Redis失败"));
        maintenance.invalidateAfterCommit(1);
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit));
        var order = inOrder(cache);
        for (String token : new String[]{"a", "b", "c"}) {
            order.verify(cache).tryLock(1);
            order.verify(cache).invalidate(1, token);
            order.verify(cache).release(1, token);
        }
        verify(cache).record("invalidation_failed");
    }

    @Test void registrationWithoutActualTransactionIsRejected() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> maintenance.invalidateAfterCommit(1));
        verifyNoInteractions(cache);
    }
}
