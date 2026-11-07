-- 元数据版本只随文章保存/首次隐藏递增，浏览和点赞更新不参与。
ALTER TABLE article ADD COLUMN meta_version BIGINT NOT NULL DEFAULT 1 COMMENT '索引元数据版本';

-- 成功交给Broker即删除；租约令牌阻止迟到回调修改重新领取的事件。
CREATE TABLE article_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    meta_version BIGINT NOT NULL,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claim_token BINARY(16) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_available (available_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章索引事务事件';
