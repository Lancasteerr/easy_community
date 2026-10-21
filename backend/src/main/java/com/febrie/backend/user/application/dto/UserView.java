package com.febrie.backend.user.application.dto;

import com.febrie.backend.user.domain.Role;

/** 对外只返回稳定身份和角色，ID使用字符串避免JavaScript精度丢失。 */
public record UserView(String userId, String username, Role role) {
    public static UserView from(AccountSnapshot account) {
        return new UserView(Long.toString(account.userId()), account.username(), account.role());
    }
}
