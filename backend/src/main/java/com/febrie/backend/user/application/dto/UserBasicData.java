package com.febrie.backend.user.application.dto;

/** 仅用于公开展示；不携带认证字段，软删除账号继续保留署名。 */
public record UserBasicData(long userId, String username, String avatarMediaId) { }
