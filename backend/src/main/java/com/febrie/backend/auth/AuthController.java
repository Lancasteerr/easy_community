package com.febrie.backend.auth;

import com.febrie.backend.common.ApiException;
import com.febrie.backend.user.AccountPolicy;
import com.febrie.backend.user.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** JSON认证入口，Session ID只通过HttpOnly Cookie传递。 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SessionService sessions;
    private final RequestLimits limits;

    public AuthController(AuthenticationManager authenticationManager, SessionService sessions, RequestLimits limits) {
        this.authenticationManager = authenticationManager;
        this.sessions = sessions;
        this.limits = limits;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        // 获取框架暴露的掩码Token，前端原样通过指定请求头返回。
        return new CsrfView(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String username = AccountPolicy.username(body.username());
        String ip = request.getRemoteAddr();
        limits.checkFailures(username, ip);
        org.springframework.security.core.Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, body.password()));
        } catch (AuthenticationException error) {
            limits.failed(username, ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        sessions.establish(authentication, request, response);
        return ((SessionPrincipal) authentication.getPrincipal()).view();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) { sessions.logout(request, response); }

    public record CsrfView(String token, String headerName) { }

    public record LoginRequest(@NotBlank @Size(max = 128) String username,
                               @NotBlank @Size(max = 256) String password) {
        @Override
        public String toString() { return "LoginRequest[credentials=REDACTED]"; }
    }
}
