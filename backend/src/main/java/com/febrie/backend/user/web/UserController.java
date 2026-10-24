package com.febrie.backend.user.web;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.web.session.SessionService;
import com.febrie.backend.user.application.UserService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.web.request.PasswordRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 本人接口不接收userId，身份完全来自已通过版本校验的认证上下文。 */
@RestController
@RequestMapping("/api/users/me")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserController {
    private final UserService users;
    private final SessionService sessions;
    public UserController(UserService users, SessionService sessions) { this.users = users; this.sessions = sessions; }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal SessionPrincipal principal, @Valid @RequestBody PasswordRequest body,
                         HttpServletRequest request, HttpServletResponse response) {
        // 用户服务只接收操作人所需字段，不依赖认证会话对象。
        var actor = new UserActor(principal.userId(), principal.role(), principal.authVersion());
        users.changePassword(actor, body.oldPassword(), body.newPassword());
        // SQL事务已提交，其余设备依靠版本比较失效；当前设备同时清除Cookie和成员。
        sessions.logout(request, response);
    }

}
