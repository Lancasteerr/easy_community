-- 日期候选扫描不再遍历文章全表；原主键继续服务按文章的七日聚合。
CREATE INDEX idx_article_view_stat_date_article ON article_view_stat(stat_date, article_id);
