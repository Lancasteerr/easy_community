package com.febrie.backend.user.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 登录身份只在持久层使用；不生成 toString，避免密码哈希进入日志。 */
@Getter
@Setter
@TableName("user_auth_identity")
public class AuthIdentityEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String provider;
    private String providerUid;
    private String passwordHash;
}
