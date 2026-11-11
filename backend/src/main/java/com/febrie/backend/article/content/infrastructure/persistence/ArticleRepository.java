package com.febrie.backend.article.content.infrastructure.persistence;

import com.febrie.backend.article.content.application.dto.ArticleSnapshot;
import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.tag.application.dto.TagView;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.febrie.backend.article.content.infrastructure.persistence.entity.ArticleEntity;
import com.febrie.backend.article.content.infrastructure.persistence.mapper.ArticleMapper;
import com.febrie.backend.media.domain.MediaIds;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 文章SQL与映射集中于此；调用者负责事务、权限及锁顺序。 */
@Repository
public class ArticleRepository {
    private final ArticleMapper articles;
    public ArticleRepository(ArticleMapper articles) { this.articles = articles; }

    public Optional<ArticleSnapshot> find(long id) { return snapshot(articles.selectById(id)); }
    public Optional<ArticleSnapshot> lock(long id) { return snapshot(articles.lock(id)); }

    /** 与文章写入共享事务和行锁；INSERT SELECT准确记录本次提交版本。 */
    public void recordIndexEvent(long id, boolean advanceVersion) {
        if (advanceVersion && articles.advanceMetaVersion(id) != 1) { throw new IllegalStateException("文章版本更新失败"); }
        if (articles.appendIndexEvent(id) != 1) { throw new IllegalStateException("文章索引事件写入失败"); }
    }

    /** 先插入不带封面的文章，避免封面外键在媒体排序锁之前提前锁文件。 */
    public long insert(long author, PreparedArticle content, Instant now) {
        var row = new ArticleEntity();
        row.setAuthorId(author);
        row.setTitle(content.title());
        row.setSummary(content.summary());
        row.setContentJson(content.contentJson());
        row.setContentHtml(content.contentHtml());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setPublishedAt(now);
        articles.insert(row);
        // 无论点赞入口是否启用，新文章始终拥有精确计数基线，并随创建事务一起回滚。
        articles.initializeLikeCount(row.getId());
        return row.getId();
    }

    public void replace(long id, PreparedArticle content, Instant now) {
        // 显式写NULL才能清封面；不能依赖实体更新默认忽略NULL的策略。
        articles.update(null, Wrappers.<ArticleEntity>lambdaUpdate().eq(ArticleEntity::getId, id)
                .set(ArticleEntity::getTitle, content.title()).set(ArticleEntity::getSummary, content.summary())
                .set(ArticleEntity::getContentJson, content.contentJson()).set(ArticleEntity::getContentHtml, content.contentHtml())
                .set(ArticleEntity::getCoverMediaId, content.coverMediaId() == null ? null : MediaIds.bytes(content.coverMediaId()))
                .set(ArticleEntity::getUpdatedAt, now));
    }

    public void hide(long id, Instant now) {
        articles.update(null, Wrappers.<ArticleEntity>lambdaUpdate().eq(ArticleEntity::getId, id)
                .set(ArticleEntity::isHidden, true).set(ArticleEntity::getUpdatedAt, now));
    }

    public void replaceTags(long id, Collection<Long> tags) {
        articles.removeTags(id);
        if (!tags.isEmpty()) { articles.addTags(id, tags); }
    }

    public Map<Long, ArticleSnapshot> listRows(Collection<Long> ids) {
        Map<Long, ArticleSnapshot> rows = new HashMap<>();
        if (!ids.isEmpty()) { articles.listRows(ids).forEach(row -> rows.put(row.getId(), row.snapshot())); }
        return rows;
    }

    public Map<Long, List<TagView>> tags(Collection<Long> ids) {
        Map<Long, List<TagView>> result = new HashMap<>();
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        if (!ids.isEmpty()) {
            articles.tags(ids).forEach(row -> result.get(row.getArticleId())
                    .add(new TagView(Long.toString(row.getTagId()), row.getName())));
        }
        return result;
    }

    public List<Long> hiddenIds(long after, int size) { return articles.hiddenIds(after, size); }

    private static Optional<ArticleSnapshot> snapshot(ArticleEntity row) {
        return Optional.ofNullable(row).map(ArticleEntity::snapshot);
    }
}
