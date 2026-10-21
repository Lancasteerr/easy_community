package com.febrie.backend.user.web;

import com.febrie.backend.auth.SessionPrincipal;
import com.febrie.backend.auth.SessionService;
import com.febrie.backend.user.application.UserService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.web.request.CreateRequest;
import com.febrie.backend.user.web.request.PasswordRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.*;

/** 确认服务端身份转换完整，并且只有改密成功后才退出当前 HTTP 会话。 */
class UserControllerTests {
    private final SessionPrincipal principal = new SessionPrincipal(
            42, "alice", Role.ADMIN, 7, Instant.EPOCH, Instant.EPOCH.plusSeconds(7200));
    private final UserActor actor = new UserActor(42, Role.ADMIN, 7);

    @Test
    void passwordChangePassesAuthenticatedActorBeforeLogout() {
        var users = mock(UserService.class);
        var sessions = mock(SessionService.class);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        new UserController(users, sessions).password(principal,
                new PasswordRequest("old-password", "new-password"), request, response);
        var order = inOrder(users, sessions);
        order.verify(users).changePassword(actor, "old-password", "new-password");
        order.verify(sessions).logout(request, response);
    }

    @Test
    void managementUsesAuthenticatedRoleAndVersion() {
        var users = mock(UserService.class);
        var controller = new AdminUserController(users);
        controller.create(principal, new CreateRequest("bob", "password-123", Role.PATROLLER));
        controller.delete(principal, 99);
        verify(users).createManaged(actor, "bob", "password-123", Role.PATROLLER);
        verify(users).deleteManaged(actor, 99);
    }
}
