package com.febrie.backend.user.web;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.user.application.UserService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.application.dto.UserView;
import com.febrie.backend.user.web.request.CreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 管理接口由URL角色授权与服务内角色边界双重保护。 */
@RestController
@RequestMapping("/api/admin/users")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminUserController {
    private final UserService users;
    public AdminUserController(UserService users) { this.users = users; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@AuthenticationPrincipal SessionPrincipal principal, @Valid @RequestBody CreateRequest body) {
        // 操作人来自已经通过校验的认证身份，不能使用请求体提供的身份信息。
        var actor = new UserActor(principal.userId(), principal.role(), principal.authVersion());
        return users.createManaged(actor, body.username(), body.password(), body.role());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable @Positive long userId) {
        var actor = new UserActor(principal.userId(), principal.role(), principal.authVersion());
        users.deleteManaged(actor, userId);
    }

}
