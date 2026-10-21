package com.febrie.backend.auth.web;

import com.febrie.backend.auth.application.AuthService;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.web.request.LoginRequest;
import com.febrie.backend.auth.web.response.CsrfView;
import com.febrie.backend.auth.web.session.SessionService;
import com.febrie.backend.user.application.dto.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** JSON认证入口，Session ID只通过HttpOnly Cookie传递。 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
    private final AuthService auth;
    private final SessionService sessions;

    public AuthController(AuthService auth, SessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        // 获取框架暴露的掩码Token，前端原样通过指定请求头返回。
        return new CsrfView(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var authentication = auth.authenticate(body.username(), body.password(), request.getRemoteAddr());
        sessions.establish(authentication, request, response);
        return ((SessionPrincipal) authentication.getPrincipal()).view();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) { sessions.logout(request, response); }

}
