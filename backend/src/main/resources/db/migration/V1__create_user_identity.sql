-- 社区身份与登录凭证分离；用户名包含已删除账号，始终不可复用。
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '社区内部用户ID',
    username VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '去首尾空白并区分大小写的用户名',
    role VARCHAR(16) NOT NULL COMMENT 'ADMIN、PATROLLER或USER',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE、DISABLED或DELETED',
    auth_version BIGINT NOT NULL DEFAULT 1 COMMENT '认证版本，与账号安全变更同事务递增',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'PATROLLER', 'USER')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    CONSTRAINT ck_users_auth_version CHECK (auth_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区用户';

-- LOCAL的provider_uid使用去首尾空白并保留大小写的用户名，未来可扩展其他身份提供商。
CREATE TABLE user_auth_identity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    provider_uid VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    password_hash VARCHAR(255) NULL COMMENT '本地密码哈希，严禁写入Redis',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_provider_uid (provider, provider_uid),
    UNIQUE KEY uk_identity_user_provider (user_id, provider),
    CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户登录身份';
