package com.febrie.backend.user.web;

import com.febrie.backend.user.application.UserProfileQueryService;
import com.febrie.backend.user.application.dto.UserMetadataView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

/** 沿用/api/**的登录要求；数字路径与me等本人入口明确区分。 */
@RestController
@RequestMapping("/api/users")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserMetadataController {
    private final UserProfileQueryService queries;

    public UserMetadataController(UserProfileQueryService queries) { this.queries = queries; }

    @GetMapping("/{userId:[0-9]+}")
    public UserMetadataView user(@PathVariable long userId) { return queries.user(userId); }
}
