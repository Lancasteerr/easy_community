package com.febrie.backend.user.application.dto;

import java.time.Instant;

/** 指定用户资料只公开展示字段；createdAt为UTC加入时间，不包含权限与认证信息。 */
public record UserMetadataView(String userId, String username, String bio, String avatarMediaId,
                               String avatarUrl, Instant createdAt) { }
