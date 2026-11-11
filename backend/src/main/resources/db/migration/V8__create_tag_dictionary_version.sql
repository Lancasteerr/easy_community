-- 标签和版本在同一事务变更，不依赖自增ID分配顺序。
CREATE TABLE tag_dictionary_meta (
    id TINYINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    CONSTRAINT ck_tag_dictionary_singleton CHECK (id = 1),
    CONSTRAINT ck_tag_dictionary_version CHECK (version > 0)
) ENGINE=InnoDB COMMENT='标签目录的单调提交版本';

INSERT INTO tag_dictionary_meta (id, version) VALUES (1, 1);
