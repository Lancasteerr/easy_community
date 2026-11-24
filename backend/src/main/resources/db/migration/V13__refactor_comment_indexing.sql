-- 保留既有计数，历史一级评论以版本 1 参与回填。
ALTER TABLE article_comment
    ADD COLUMN index_version BIGINT NOT NULL DEFAULT 1,
    ADD KEY idx_comment_active_time (article_id, root_id, deleted, created_at, id),
    ADD KEY idx_comment_root_backfill (root_id, id);

CREATE TABLE comment_write_result (
    article_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    comment_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    PRIMARY KEY (article_id, actor_id, operation_id)
) ENGINE=InnoDB COMMENT='评论写命令的持久化幂等结果';

CREATE TABLE comment_index_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    index_version BIGINT NOT NULL,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    KEY idx_comment_index_due (available_at, id)
) ENGINE=InnoDB COMMENT='一级评论创建及删除索引事件';

CREATE TABLE comment_like_index_outbox (
    comment_id BIGINT NOT NULL PRIMARY KEY,
    index_version BIGINT NOT NULL,
    available_at DATETIME(3) NOT NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    KEY idx_comment_like_index_due (available_at, comment_id)
) ENGINE=InnoDB COMMENT='按评论合并的热门排序同步任务';
