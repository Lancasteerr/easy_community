package com.febrie.backend.user.web;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.user.application.UserProfileService;
import com.febrie.backend.user.application.dto.*;
import com.febrie.backend.user.web.request.AvatarRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 实时资料与头像入口，认证身份来自Session，资料本身从主库重新读取。 */
@RestController
@RequestMapping("/api/users/me")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserProfileController {
    private final UserProfileService profiles;

    public UserProfileController(UserProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    public UserProfileView me(@AuthenticationPrincipal SessionPrincipal principal) { return profiles.profile(actor(principal)); }

    @PutMapping("/avatar")
    public UserProfileView avatar(@AuthenticationPrincipal SessionPrincipal principal, @Valid @RequestBody AvatarRequest request) {
        return profiles.setAvatar(actor(principal), request.mediaId());
    }

    @DeleteMapping("/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAvatar(@AuthenticationPrincipal SessionPrincipal principal) { profiles.clearAvatar(actor(principal)); }

    private static UserActor actor(SessionPrincipal principal) {
        return new UserActor(principal.userId(), principal.role(), principal.authVersion());
    }
}
