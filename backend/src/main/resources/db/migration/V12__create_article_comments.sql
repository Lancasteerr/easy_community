-- 一级评论计入文章元信息；回复与已删除占位不计数。
ALTER TABLE article ADD COLUMN comment_count BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_article_comment_count CHECK (comment_count >= 0);

CREATE TABLE article_comment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    root_id BIGINT NOT NULL DEFAULT 0,
    reply_to_id BIGINT NULL,
    author_id BIGINT NOT NULL,
    content VARCHAR(300) NULL,
    created_at DATETIME(3) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    KEY idx_comment_time (article_id, root_id, created_at, id),
    CONSTRAINT fk_comment_article FOREIGN KEY (article_id) REFERENCES article(id),
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT ck_comment_reply CHECK ((root_id = 0 AND reply_to_id IS NULL) OR
                                       (root_id > 0 AND reply_to_id IS NOT NULL)),
    CONSTRAINT ck_comment_content CHECK ((deleted = TRUE AND content IS NULL) OR
                                         (deleted = FALSE AND content IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='两级评论与软删除占位';

CREATE TABLE comment_like_state (
    user_id BIGINT NOT NULL,
    comment_id BIGINT NOT NULL,
    liked BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, comment_id),
    CONSTRAINT ck_comment_like_state CHECK (liked IN (0, 1) AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论点赞关系及最高版本';

CREATE TABLE comment_like_count (
    comment_id BIGINT NOT NULL PRIMARY KEY,
    like_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_comment_like_count CHECK (like_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一级评论已提交点赞数';

CREATE TABLE comment_visibility_repair (
    comment_id BIGINT NOT NULL PRIMARY KEY,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_comment_repair_due (next_attempt_at, comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='删除墓碑尚待发布的评论';
