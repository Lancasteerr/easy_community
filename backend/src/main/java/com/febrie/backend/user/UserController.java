package com.febrie.backend.user;

import com.febrie.backend.auth.SessionPrincipal;
import com.febrie.backend.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    @GetMapping
    public UserView me(@AuthenticationPrincipal SessionPrincipal principal) { return principal.view(); }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal SessionPrincipal principal, @Valid @RequestBody PasswordRequest body,
                         HttpServletRequest request, HttpServletResponse response) {
        users.changePassword(principal, body.oldPassword(), body.newPassword());
        // SQL事务已提交，其余设备依靠版本比较失效；当前设备同时清除Cookie和成员。
        sessions.logout(request, response);
    }

    public record PasswordRequest(@NotBlank @Size(max = 256) String oldPassword,
                                  @NotBlank @Size(max = 256) String newPassword) {
        @Override
        public String toString() { return "PasswordRequest[credentials=REDACTED]"; }
    }
}
