-- 取消后保留版本墓碑；只保存当前状态，不保存逐次点击历史。
CREATE TABLE article_like_state (
    user_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    liked TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, article_id),
    CONSTRAINT ck_like_status CHECK (liked IN (0, 1)),
    CONSTRAINT ck_like_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户文章点赞当前状态和最高版本';

CREATE TABLE article_like_count (
    article_id BIGINT NOT NULL PRIMARY KEY,
    like_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_like_count CHECK (like_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='与关系同事务维护的精确计数';

-- 升级时包含隐藏文章；以后隐藏和注销都不撤销已经受理的命令。
INSERT INTO article_like_count(article_id, like_count) SELECT id, 0 FROM article;

CREATE TABLE like_version_epoch (
    id TINYINT NOT NULL PRIMARY KEY,
    epoch INT NOT NULL,
    CONSTRAINT ck_like_epoch_id CHECK (id = 1),
    CONSTRAINT ck_like_epoch_range CHECK (epoch >= 0 AND epoch < 1048576)
) ENGINE=InnoDB COMMENT='人工恢复持久化纪元';
INSERT INTO like_version_epoch(id, epoch) VALUES (1, 0);
