package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.dto.ArticleDetailData;
import com.febrie.backend.article.content.application.dto.ArticleEditView;
import com.febrie.backend.article.content.application.dto.ArticleBriefData;
import com.febrie.backend.article.content.application.dto.ArticlePageIds;
import com.febrie.backend.article.content.application.dto.ArticleSnapshot;
import com.febrie.backend.article.content.application.dto.LoadedArticle;

import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.user.application.dto.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** 独立代理建立短只读RR视图；缓存调用方必须先获得缓存锁，再调用此Bean。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ArticleReader {
    private final ArticleRepository articles;
    private final MediaUrls urls;
    private final ObjectMapper mapper;

    public ArticleReader(ArticleRepository articles, MediaUrls urls, ObjectMapper mapper) {
        this.articles = articles; this.urls = urls; this.mapper = mapper;
    }
    public LoadedArticle<ArticleDetailData> detail(long id) {
        var row = articles.find(id).orElse(null);
        if (row == null) { return LoadedArticle.missing(); }
        if (row.hidden()) { return LoadedArticle.hidden(); }
        var tags = articles.tags(List.of(id)).get(id);
        return LoadedArticle.visible(new ArticleDetailData(Long.toString(id), row.title(), Long.toString(row.authorId()),
                row.summary(), row.coverMediaId(), tags,
                row.publishedAt(), row.updatedAt(), row.contentHtml()));
    }
    public Map<Long, LoadedArticle<ArticleBriefData>> lists(Collection<Long> ids) {
        Map<Long, ArticleSnapshot> rows = articles.listRows(ids);
        var tags = articles.tags(rows.keySet());
        Map<Long, LoadedArticle<ArticleBriefData>> result = new HashMap<>();
        for (long id : ids) {
            var row = rows.get(id);
            if (row == null) { result.put(id, LoadedArticle.missing()); }
            else if (row.hidden()) { result.put(id, LoadedArticle.hidden()); }
            else { result.put(id, LoadedArticle.visible(new ArticleBriefData(Long.toString(id), row.title(),
                    Long.toString(row.authorId()), row.summary(), row.coverMediaId(),
                    tags.get(id), row.publishedAt(), row.updatedAt()))); }
        }
        return result;
    }
    public ArticleEditView edit(UserActor actor, long id) {
        var row = articles.find(id).orElseThrow(ArticleErrors::notFound);
        if (row.authorId() != actor.userId()) { throw ArticleErrors.forbidden(); }
        if (row.hidden()) { throw ArticleErrors.hidden(); }
        return new ArticleEditView(Long.toString(id), row.title(), mapper.readTree(row.contentJson()),
                row.coverMediaId(), url(row), articles.tags(List.of(id)).get(id), row.createdAt(), row.updatedAt(), row.publishedAt());
    }
    public ArticlePageIds page(long author, int page, int size) {
        return new ArticlePageIds(articles.pageIds(author, ((long) page - 1) * size, size), articles.countPublic(author));
    }
    private String url(ArticleSnapshot row) { return row.coverMediaId() == null ? null : urls.publicUrl(row.coverMediaId()); }
}
