package com.febrie.backend.auth.application;

import com.febrie.backend.auth.infrastructure.ratelimit.RequestLimits;
import com.febrie.backend.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 登录流程提取后仍保持校验顺序、密码原值及认证失败的计数语义。 */
class AuthServiceTests {
    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final RequestLimits limits = mock(RequestLimits.class);
    private final AuthService service = new AuthService(manager, limits);

    @Test
    void normalizesUsernameAndChecksLimitsBeforeAuthentication() {
        var authenticated = UsernamePasswordAuthenticationToken.authenticated("alice", null, java.util.List.of());
        when(manager.authenticate(any())).thenReturn(authenticated);
        assertSame(authenticated, service.authenticate(" Alice ", " password ", "127.0.0.1"));
        var order = inOrder(limits, manager);
        order.verify(limits).checkFailures("alice", "127.0.0.1");
        var request = ArgumentCaptor.forClass(Authentication.class);
        order.verify(manager).authenticate(request.capture());
        assertEquals("alice", request.getValue().getName());
        assertEquals(" password ", request.getValue().getCredentials());
        verify(limits, never()).failed(anyString(), anyString());
    }

    @Test
    void countsAuthenticationFailureAndReturnsStablePublicError() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("internal detail"));
        var error = assertThrows(ApiException.class,
                () -> service.authenticate(" Alice ", "wrong", "127.0.0.1"));
        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("INVALID_CREDENTIALS", error.code());
        assertFalse(error.getMessage().contains("internal detail"));
        var order = inOrder(limits, manager);
        order.verify(limits).checkFailures("alice", "127.0.0.1");
        order.verify(manager).authenticate(any());
        order.verify(limits).failed("alice", "127.0.0.1");
    }

    @Test
    void rateLimitedAttemptNeverVerifiesPasswordOrAddsFailure() {
        var error = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "稍后重试", 30);
        doThrow(error).when(limits).checkFailures("alice", "127.0.0.1");
        assertSame(error, assertThrows(ApiException.class,
                () -> service.authenticate("alice", "password", "127.0.0.1")));
        verifyNoInteractions(manager);
        verify(limits, never()).failed(anyString(), anyString());
    }

    @Test
    void databaseFailurePropagatesWithoutCountingWrongPassword() {
        var error = new DataAccessResourceFailureException("unavailable");
        when(manager.authenticate(any())).thenThrow(error);
        assertSame(error, assertThrows(DataAccessResourceFailureException.class,
                () -> service.authenticate("alice", "password", "127.0.0.1")));
        verify(limits, never()).failed(anyString(), anyString());
    }

    @Test
    void invalidUsernameDoesNotReachLimitsOrAuthentication() {
        var error = assertThrows(ApiException.class,
                () -> service.authenticate("!", "password", "127.0.0.1"));
        assertEquals("INVALID_USERNAME", error.code());
        verifyNoInteractions(limits, manager);
    }
}
