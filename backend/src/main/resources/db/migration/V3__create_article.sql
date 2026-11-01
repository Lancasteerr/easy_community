-- 删除仅隐藏且不可恢复；媒体引用继续保留，展示URL不进入数据库。
CREATE TABLE article (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    summary VARCHAR(200) NOT NULL,
    content_json JSON NOT NULL,
    content_html MEDIUMTEXT NOT NULL,
    cover_media_id BINARY(16) NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    published_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_article_author_public (author_id, hidden, id),
    CONSTRAINT fk_article_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT fk_article_cover FOREIGN KEY (cover_media_id) REFERENCES media_file(id) ON DELETE RESTRICT,
    CONSTRAINT ck_article_hidden CHECK (hidden IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章内容与不可恢复隐藏状态';

CREATE TABLE tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员维护的最小标签目录';

CREATE TABLE article_tag (
    article_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (article_id, tag_id),
    KEY idx_article_tag_reverse (tag_id, article_id),
    CONSTRAINT fk_article_tag_article FOREIGN KEY (article_id) REFERENCES article(id),
    CONSTRAINT fk_article_tag_tag FOREIGN KEY (tag_id) REFERENCES tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章标签去重关系';
