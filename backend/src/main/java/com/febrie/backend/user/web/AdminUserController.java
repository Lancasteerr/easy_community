package com.febrie.backend.user.web;

import com.febrie.backend.auth.SessionPrincipal;
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
        return users.createManaged(new UserActor(principal.userId(), principal.role(), principal.authVersion()), body.username(), body.password(), body.role());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable @Positive long userId) {
        users.deleteManaged(new UserActor(principal.userId(), principal.role(), principal.authVersion()), userId);
    }

}
