-- V2仅供空库初始化，不兼容旧版Flyway历史，不自动清空旧文件和头像。
CREATE TABLE media_file (
    id BINARY(16) NOT NULL COMMENT '标准UUID的16字节表示，不交换字节',
    uploader_id BIGINT NOT NULL COMMENT '认证上下文中的上传者',
    purpose VARCHAR(24) NOT NULL COMMENT 'AVATAR或ARTICLE_IMAGE',
    status VARCHAR(16) NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED、READY或DELETING',
    mime_type VARCHAR(64) NULL COMMENT '可靠真实类型未知时为空，不能直接记入声明类型',
    size_bytes BIGINT UNSIGNED NULL COMMENT '可信通知中的实际对象大小',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
    next_action_at DATETIME(3) NULL COMMENT '按状态解释的下次维护时间，引用中READY为空',
    PRIMARY KEY (id),
    KEY idx_media_maintenance (status, next_action_at),
    CONSTRAINT ck_media_status CHECK (status IN ('ISSUED','READY','DELETING')),
    CONSTRAINT ck_media_purpose CHECK (purpose IN ('AVATAR','ARTICLE_IMAGE')),
    CONSTRAINT ck_media_size CHECK (size_bytes IS NULL OR size_bytes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件身份、可信元数据与回收调度';

-- 多态实体的存在性、权限与业务行锁由调用方保证，文件外键禁止有引用时删行。
CREATE TABLE media_reference (
    file_id BINARY(16) NOT NULL COMMENT '文件UUID',
    ref_type VARCHAR(24) NOT NULL COMMENT 'USER_AVATAR或ARTICLE_IMAGE',
    ref_id BIGINT NOT NULL COMMENT '用户ID或文章ID',
    PRIMARY KEY (ref_type, ref_id, file_id),
    KEY idx_media_ref_file (file_id),
    CONSTRAINT fk_media_reference_file FOREIGN KEY (file_id) REFERENCES media_file(id) ON DELETE RESTRICT,
    CONSTRAINT ck_media_ref_type CHECK (ref_type IN ('USER_AVATAR','ARTICLE_IMAGE')),
    CONSTRAINT ck_media_ref_id CHECK (ref_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当前有效的业务文件引用';

-- 同一短事务维护头像和引用，用户行锁保证每用户只有一个当前头像。
ALTER TABLE users ADD COLUMN avatar_media_id BINARY(16) NULL COMMENT '当前头像文件UUID',
    ADD CONSTRAINT fk_users_avatar FOREIGN KEY (avatar_media_id) REFERENCES media_file(id) ON DELETE RESTRICT;
