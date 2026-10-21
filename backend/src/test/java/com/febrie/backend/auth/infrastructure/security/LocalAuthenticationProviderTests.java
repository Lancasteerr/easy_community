package com.febrie.backend.auth.infrastructure.security;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import com.febrie.backend.user.application.AccountQueryService;
import com.febrie.backend.user.application.dto.AccountSnapshot;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 认证只能保留密码验证时的版本和角色，不能再次读取后把旧认证升级。 */
class LocalAuthenticationProviderTests {
    @Test
    void preservesSnapshotAndNeverCarriesPasswordIntoPrincipal() {
        var accounts = mock(AccountQueryService.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy");
        when(encoder.matches("password", "secret-hash")).thenReturn(true);
        when(accounts.findByUsername("alice")).thenReturn(Optional.of(
                new AccountSnapshot(1, "alice", Role.PATROLLER, "ACTIVE", 7, "secret-hash")));
        var now = Instant.parse("2026-10-21T00:00:00Z");
        var properties = new AuthProperties("test", "ec:test", false, Duration.ofHours(2), 60, 30, 120, 5, 30, Duration.ofMinutes(10));
        var provider = new LocalAuthenticationProvider(accounts, encoder, Clock.fixed(now, ZoneOffset.UTC), properties);
        var authentication = provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "password"));
        var principal = (SessionPrincipal) authentication.getPrincipal();
        assertEquals(7, principal.authVersion());
        assertEquals(Role.PATROLLER, principal.role());
        assertEquals(now.plusSeconds(7200), principal.expiresAt());
        assertNull(authentication.getCredentials());
        assertFalse(principal.toString().contains("secret-hash"));
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PATROLLER")));
        verify(accounts, times(1)).findByUsername("alice");
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void unknownAccountStillVerifiesDummyHash() {
        var accounts = mock(AccountQueryService.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        when(accounts.findByUsername("missing")).thenReturn(Optional.empty());
        var properties = new AuthProperties("test", "ec:test", false, Duration.ofHours(2), 60, 30, 120, 5, 30, Duration.ofMinutes(10));
        var provider = new LocalAuthenticationProvider(accounts, encoder, Clock.systemUTC(), properties);
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("missing", "password")));
        verify(encoder).matches("password", "dummy-hash");
    }
}
