-- 维护窗口执行：停止旧写实例后回填，旧关系表仅保留供发布核对。
ALTER TABLE article ADD COLUMN tag_ids JSON NULL COMMENT '最多五个标签ID，数字数组';

-- 使用窗口聚合显式固定数值顺序，避免普通JSON_ARRAYAGG的不确定顺序。
UPDATE article a
LEFT JOIN (
    SELECT article_id, ids FROM (
        SELECT article_id,
               JSON_ARRAYAGG(tag_id) OVER (
                   PARTITION BY article_id ORDER BY tag_id
                   ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
               ) AS ids,
               ROW_NUMBER() OVER (PARTITION BY article_id ORDER BY tag_id) AS position_in_article
        FROM article_tag
    ) ordered_tags WHERE position_in_article = 1
) r ON r.article_id = a.id
SET a.tag_ids = COALESCE(r.ids, JSON_ARRAY());

-- 旧关系有外键和去重主键；超五项的数据在此中断迁移，不能截断。
ALTER TABLE article
    MODIFY COLUMN tag_ids JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '最多五个标签ID，数字数组',
    ADD CONSTRAINT ck_article_tag_ids CHECK (JSON_TYPE(tag_ids) = 'ARRAY' AND JSON_LENGTH(tag_ids) <= 5);
