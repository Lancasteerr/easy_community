package com.febrie.backend.user.application.dto;

/** 每次鉴权读取的账户状态与安全版本，不包含密码或新的角色信息。 */
public record AccountAuthState(String status, long authVersion) {
}
