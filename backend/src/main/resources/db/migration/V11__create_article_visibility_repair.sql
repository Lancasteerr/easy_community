-- 隐藏与待办同事务提交，Redis失败后可从主库重新领取。
CREATE TABLE article_visibility_repair (
    article_id BIGINT NOT NULL PRIMARY KEY,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_visibility_repair_available (available_at, article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章隐藏墓碑补写待办';
