package com.febrie.backend.auth;

import com.febrie.backend.common.ApiException;
import com.febrie.backend.user.application.AccountQueryService;
import com.febrie.backend.user.application.dto.AccountAuthState;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证撤销和绝对超时条件，明确不会重新读取角色或自动重新登记会话。 */
class SessionValidationTests {
    @Test
    void currentVersionAllowsSnapshotRoleButVersionChangeRevokesIt() {
        var accounts = mock(AccountQueryService.class);
        var sessions = mock(ActiveSessions.class);
        var now = Instant.parse("2026-10-21T00:00:00Z");
        var principal = new SessionPrincipal(1, "alice", Role.ADMIN, 7, now, now.plusSeconds(7200));
        var validation = new SessionValidation(accounts, sessions, Clock.fixed(now, ZoneOffset.UTC));
        when(accounts.authState(1)).thenReturn(Optional.of(new AccountAuthState("ACTIVE", 7)));
        when(sessions.contains(principal, "sid")).thenReturn(true);
        assertDoesNotThrow(() -> validation.validate(principal, "sid"));
        when(accounts.authState(1)).thenReturn(Optional.of(new AccountAuthState("ACTIVE", 8)));
        assertThrows(ApiException.class, () -> validation.validate(principal, "sid"));
        verify(accounts, never()).findByUsername(anyString());
        verify(sessions, never()).register(any(), any());
    }

    @Test
    void revokedMembershipAndExactExpiryFailClosed() {
        var accounts = mock(AccountQueryService.class);
        var sessions = mock(ActiveSessions.class);
        var now = Instant.parse("2026-10-21T00:00:00Z");
        var principal = new SessionPrincipal(1, "alice", Role.USER, 1, now, now.plusSeconds(1));
        when(accounts.authState(1)).thenReturn(Optional.of(new AccountAuthState("ACTIVE", 1)));
        assertThrows(ApiException.class, () -> new SessionValidation(accounts, sessions,
                Clock.fixed(now, ZoneOffset.UTC)).validate(principal, "revoked"));
        reset(accounts, sessions);
        assertThrows(ApiException.class, () -> new SessionValidation(accounts, sessions,
                Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC)).validate(principal, "expired"));
        verifyNoInteractions(accounts, sessions);
    }
}
