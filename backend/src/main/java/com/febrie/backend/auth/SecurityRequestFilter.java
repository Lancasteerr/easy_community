package com.febrie.backend.auth;

import com.febrie.backend.common.ApiException;
import com.febrie.backend.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 入口限流先于CSRF建会话；认证快照校验先于URL授权和业务执行。 */
public class SecurityRequestFilter extends OncePerRequestFilter {
    private final RequestLimits limits;
    private final SessionValidation validation;
    private final SessionService sessions;
    private final AuthProperties properties;

    public SecurityRequestFilter(RequestLimits limits, SessionValidation validation, SessionService sessions, AuthProperties properties) {
        this.limits = limits;
        this.validation = validation;
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        // 此处只使用容器已经处理的连接地址，不自行解析XFF或X-Real-IP。
        String ip = request.getRemoteAddr();
        if ("GET".equals(request.getMethod()) && "/api/auth/csrf".equals(path)) {
            limits.consume("csrf", ip, properties.csrfPerMinute());
        } else if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(path)) {
            limits.consume("login", ip, properties.loginPerMinute());
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            var session = request.getSession(false);
            try {
                if (session == null) { throw ApiException.unauthorized(); }
                validation.validate(principal, session.getId());
            } catch (ApiException invalid) {
                sessions.logout(request, response);
                throw invalid;
            }
            limits.consume("user", principal.getName(), properties.userPerMinute());
        }
        chain.doFilter(request, response);
    }
}
