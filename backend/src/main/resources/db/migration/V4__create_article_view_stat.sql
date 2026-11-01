-- 内容编辑与统计分别更新；总量只由日累计差值刷盘维护。
ALTER TABLE article
    ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0 COMMENT '已落库总浏览量',
    ADD CONSTRAINT ck_article_view_count CHECK (view_count >= 0);

CREATE TABLE article_view_stat (
    article_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    pv BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (article_id, stat_date),
    CONSTRAINT ck_article_view_stat_pv CHECK (pv >= 0),
    CONSTRAINT fk_article_view_stat_article FOREIGN KEY (article_id) REFERENCES article(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章每日已落库累计浏览量';
