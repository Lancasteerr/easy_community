package com.febrie.backend.user.application.dto;

import com.febrie.backend.user.domain.Role;
import java.time.Instant;

/** 本人资料响应；createdAt为UTC加入时间，写入响应取事务最新值，角色不进入展示缓存。 */
public record UserProfileView(String userId, String username, Role role, String avatarMediaId,
                              String avatarUrl, String bio, Instant createdAt) { }
