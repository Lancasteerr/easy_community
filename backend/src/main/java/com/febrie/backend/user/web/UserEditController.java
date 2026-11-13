package com.febrie.backend.user.web;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.user.application.UserProfileService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.application.dto.UserProfileView;
import com.febrie.backend.user.web.request.UserEditRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** 统一文字资料入口不处理上传文件，操作目标完全由Session确定。 */
@RestController
@RequestMapping("/api/users")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserEditController {
    private final UserProfileService profiles;
    private final ObjectMapper mapper;

    public UserEditController(UserProfileService profiles, ObjectMapper mapper) {
        this.profiles = profiles;
        this.mapper = mapper;
    }

    @PatchMapping(value = "/edit", consumes = "application/json")
    public UserProfileView edit(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody byte[] body) {
        var request = UserEditRequest.parse(body, mapper);
        var actor = new UserActor(principal.userId(), principal.role(), principal.authVersion());
        return profiles.editBio(actor, request.bio());
    }
}
