-- 文件身份与对象存储位置分离；临时对象由桶生命周期清理，持久对象按引用回收。
CREATE TABLE media_file (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机UUID文件身份',
    owner_user_id BIGINT NOT NULL COMMENT '上传者，来自认证上下文',
    purpose VARCHAR(32) NOT NULL COMMENT 'AVATAR、ARTICLE_COVER或ARTICLE_IMAGE',
    filename VARCHAR(255) NOT NULL COMMENT '展示名称，不参与对象路径生成',
    declared_content_type VARCHAR(64) NOT NULL COMMENT '客户端声明类型，本版不验证真实格式',
    declared_size BIGINT NOT NULL,
    actual_size BIGINT NULL,
    etag VARCHAR(255) NULL COMMENT '服务商对象标识，不假设为MD5',
    storage_provider VARCHAR(16) NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    temp_key VARCHAR(512) NOT NULL,
    public_key VARCHAR(512) NOT NULL COMMENT '复制前即登记，避免失败后失去对象定位',
    status VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
    upload_expires_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL COMMENT '临时文件业务期限，首次申请后24小时',
    operation_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP(6) NULL,
    failure_code VARCHAR(64) NULL COMMENT '仅保存内部错误码，不保存SDK异常或签名',
    unreferenced_at TIMESTAMP(6) NULL COMMENT '持久文件最后失去全部引用的时间',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_media_owner (owner_user_id, created_at),
    KEY ix_media_maintenance (status, next_retry_at, lease_until),
    KEY ix_media_expiration (status, expires_at),
    CONSTRAINT fk_media_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT ck_media_purpose CHECK (purpose IN ('AVATAR', 'ARTICLE_COVER', 'ARTICLE_IMAGE')),
    CONSTRAINT ck_media_provider CHECK (storage_provider IN ('minio', 'cos')),
    CONSTRAINT ck_media_status CHECK (status IN ('ISSUED','UPLOADED','PROMOTING','READY','DELETING','DELETED','REJECTED','EXPIRED')),
    CONSTRAINT ck_media_sizes CHECK (declared_size > 0 AND (actual_size IS NULL OR actual_size > 0)),
    CONSTRAINT ck_media_retry CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据和生命周期';

-- 一行表示一个实体与文件的有效关联，不记录正文出现次数或封面位置。
CREATE TABLE media_reference (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reference_type VARCHAR(16) NOT NULL COMMENT 'USER_AVATAR或ARTICLE',
    entity_id BIGINT NOT NULL COMMENT '用户ID或文章ID',
    media_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 文章行生成NULL，MySQL唯一索引允许多个NULL；头像行只能保留一个当前用户ID。
    avatar_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN reference_type = 'USER_AVATAR' THEN entity_id ELSE NULL END
    ) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_reference_entity_file (reference_type, entity_id, media_id),
    UNIQUE KEY uk_media_reference_current_avatar (avatar_user_id),
    KEY ix_media_reference_file (media_id),
    CONSTRAINT fk_reference_media FOREIGN KEY (media_id) REFERENCES media_file(id),
    CONSTRAINT ck_reference_type CHECK (reference_type IN ('USER_AVATAR','ARTICLE')),
    CONSTRAINT ck_reference_entity CHECK (entity_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当前有效的业务文件关联';

-- 快速读取当前头像，与引用表由用户应用服务在同一事务维护。
ALTER TABLE users ADD COLUMN avatar_media_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前头像文件ID',
    ADD CONSTRAINT fk_users_avatar FOREIGN KEY (avatar_media_id) REFERENCES media_file(id);
