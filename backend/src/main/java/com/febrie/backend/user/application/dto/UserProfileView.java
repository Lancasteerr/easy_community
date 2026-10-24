package com.febrie.backend.user.application.dto;

import com.febrie.backend.user.domain.Role;

/** 实时用户资料；与登录身份响应分离，头像变化不需要改写Session。 */
public record UserProfileView(String userId, String username, Role role, String avatarMediaId, String avatarUrl) { }
