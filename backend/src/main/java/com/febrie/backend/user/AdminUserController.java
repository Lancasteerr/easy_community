package com.febrie.backend.user;

import com.febrie.backend.auth.SessionPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
        return users.createManaged(principal, body.username(), body.password(), body.role());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable @Positive long userId) {
        users.deleteManaged(principal, userId);
    }

    public record CreateRequest(@NotBlank @Size(max = 128) String username,
                                @NotBlank @Size(max = 256) String password, Role role) {
        @Override
        public String toString() { return "CreateRequest[credentials=REDACTED]"; }
    }
}
