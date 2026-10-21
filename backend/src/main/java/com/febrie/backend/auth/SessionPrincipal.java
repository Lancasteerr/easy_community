package com.febrie.backend.auth;

import com.febrie.backend.user.application.dto.UserView;
import com.febrie.backend.user.domain.Role;
import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;

/** 登录时固定的认证快照；禁止在普通请求中更新版本或角色。 */
public record SessionPrincipal(long userId, String username, Role role, long authVersion,
                               Instant authenticatedAt, Instant expiresAt) implements Serializable, Principal {
    @Override
    public String getName() { return Long.toString(userId); }
    public UserView view() { return new UserView(getName(), username, role); }
}
