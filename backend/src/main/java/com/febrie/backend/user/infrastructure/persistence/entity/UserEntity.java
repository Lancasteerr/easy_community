package com.febrie.backend.user.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 用户表映射；不启用自动逻辑删除，保留认证读取已删除账号的原有语义。 */
@Getter
@Setter
@TableName("users")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** 公开展示简介，空字符串表示尚未填写。 */
    private String bio;
    private Role role;
    private String status;
    private Long authVersion;
    private byte[] avatarMediaId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
