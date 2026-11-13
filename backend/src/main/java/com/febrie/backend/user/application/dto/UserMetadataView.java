package com.febrie.backend.user.application.dto;

/** 指定用户资料只公开展示字段；权限与认证信息不属于此响应。 */
public record UserMetadataView(String userId, String username, String bio, String avatarMediaId, String avatarUrl) { }
