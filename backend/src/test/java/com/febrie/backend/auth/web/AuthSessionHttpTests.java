package com.febrie.backend.auth.web;

import com.febrie.backend.auth.application.AuthService;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.web.session.SessionService;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证最小身份协议与大整数精度；匿名和会话有效性由真实HTTP集成测试覆盖。 */
class AuthSessionHttpTests {
    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(Role.class)
    void sessionReturnsOnlyStringIdAndRoleWithoutAuthenticationWrites(Role role) throws Exception {
        var auth = mock(AuthService.class);
        var sessions = mock(SessionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, sessions))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var now = Instant.now();
        var principal = new SessionPrincipal(9007199254740995L, "private_username", role, 7,
                now, now.plusSeconds(7200));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of()));

        // 不携带CSRF，严格检查整个JSON，防止后续扩充资料或内部认证字段。
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.userId").value("9007199254740995"))
                .andExpect(jsonPath("$.role").value(role.name()));
        verifyNoInteractions(auth, sessions);
    }
}
