package com.febrie.backend.auth.web.session;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import com.febrie.backend.auth.infrastructure.session.ActiveSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

/** 新建会话完成即时持久化后才登记Set；认证版本不会因请求回写而升级。 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionService {
    private final ActiveSessions sessions;
    private final HttpSessionSecurityContextRepository contexts;
    private final AuthProperties properties;

    public SessionService(ActiveSessions sessions, HttpSessionSecurityContextRepository contexts, AuthProperties properties) {
        this.sessions = sessions;
        this.contexts = contexts;
        this.properties = properties;
    }

    public void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        invalidate(request, response, false);
        var principal = (SessionPrincipal) authentication.getPrincipal();
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(Math.toIntExact(properties.sessionTtl().toSeconds()));
        session.setAttribute("auth.expiresAt", principal.expiresAt().toEpochMilli());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        // flush-mode=immediate使认证数据先于集合成员持久化；失败时孤立Hash不可通过鉴权。
        sessions.register(principal, session.getId());
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        invalidate(request, response, true);
    }

    private void invalidate(HttpServletRequest request, HttpServletResponse response, boolean clearCookie) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            var saved = previous.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (saved instanceof org.springframework.security.core.context.SecurityContext context
                    && context.getAuthentication() != null
                    && context.getAuthentication().getPrincipal() instanceof SessionPrincipal principal) {
                sessions.remove(principal, previous.getId());
            }
            previous.invalidate();
        }
        SecurityContextHolder.clearContext();
        if (clearCookie) {
            response.addHeader("Set-Cookie", ResponseCookie.from("SESSION", "").path("/").httpOnly(true)
                    .secure(properties.secureCookie()).sameSite("Lax").maxAge(0).build().toString());
        }
    }
}
