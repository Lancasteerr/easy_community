package com.febrie.backend.user.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.Setter;

/** 认证联表结果；只在持久层短暂持有密码哈希，不对外暴露。 */
@Getter
@Setter
public class AccountRow extends UserEntity {
    private String passwordHash;
}
