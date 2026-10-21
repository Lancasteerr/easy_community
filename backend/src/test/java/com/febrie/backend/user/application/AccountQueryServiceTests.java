package com.febrie.backend.user.application;

import com.febrie.backend.user.application.dto.AccountAuthState;
import com.febrie.backend.user.application.dto.AccountSnapshot;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 查询入口必须保留单次登录快照，并在每次鉴权时重新读取安全版本。 */
class AccountQueryServiceTests {
    @Test
    void loginReadsOneSnapshotAndPreservesMissingAccounts() {
        var repository = mock(AccountRepository.class);
        var snapshot = new AccountSnapshot(1, "alice", Role.USER, "ACTIVE", 7, "hash");
        when(repository.findByUsername("alice")).thenReturn(Optional.of(snapshot));
        when(repository.findByUsername("missing")).thenReturn(Optional.empty());
        var service = new AccountQueryService(repository);
        assertSame(snapshot, service.findByUsername("alice").orElseThrow());
        assertTrue(service.findByUsername("missing").isEmpty());
        verify(repository).findByUsername("alice");
        verify(repository).findByUsername("missing");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void validationReadsLatestStateWithoutReusingPreviousVersion() {
        var repository = mock(AccountRepository.class);
        when(repository.authState(1)).thenReturn(
                Optional.of(new AccountAuthState("ACTIVE", 7)),
                Optional.of(new AccountAuthState("ACTIVE", 8)), Optional.empty());
        var service = new AccountQueryService(repository);
        assertEquals(7, service.authState(1).orElseThrow().authVersion());
        assertEquals(8, service.authState(1).orElseThrow().authVersion());
        assertTrue(service.authState(1).isEmpty());
        verify(repository, times(3)).authState(1);
        verifyNoMoreInteractions(repository);
    }
}
