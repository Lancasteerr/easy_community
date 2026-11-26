-- 一个事件表示文章的一级评论数待重算，同篇文章可在消费批次内合并。
CREATE TABLE comment_count_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    KEY idx_comment_count_due (available_at, id)
) ENGINE=InnoDB COMMENT='一级评论新增和删除后的文章计数重算事件';
