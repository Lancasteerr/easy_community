package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.dto.ArticleDetailView;
import com.febrie.backend.article.content.application.dto.ArticleEditView;
import com.febrie.backend.article.content.application.dto.ArticleListView;
import com.febrie.backend.article.content.application.dto.ArticlePageIds;
import com.febrie.backend.article.content.application.dto.ArticleSnapshot;
import com.febrie.backend.article.content.application.dto.LoadedArticle;

import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.user.application.AccountQueryService;
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
    private final AccountQueryService accounts;
    private final MediaUrls urls;
    private final ObjectMapper mapper;

    public ArticleReader(ArticleRepository articles, AccountQueryService accounts, MediaUrls urls, ObjectMapper mapper) {
        this.articles = articles; this.accounts = accounts; this.urls = urls; this.mapper = mapper;
    }
    public LoadedArticle<ArticleDetailView> detail(long id) {
        var row = articles.find(id).orElse(null);
        if (row == null) { return LoadedArticle.missing(); }
        if (row.hidden()) { return LoadedArticle.hidden(); }
        var names = accounts.displays(Set.of(row.authorId()));
        var tags = articles.tags(List.of(id)).get(id);
        return LoadedArticle.visible(new ArticleDetailView(Long.toString(id), row.title(), Long.toString(row.authorId()),
                author(names, row.authorId()), row.summary(), row.coverMediaId(), url(row), tags,
                row.publishedAt(), row.updatedAt(), row.contentHtml()));
    }
    public Map<Long, LoadedArticle<ArticleListView>> lists(Collection<Long> ids) {
        Map<Long, ArticleSnapshot> rows = articles.listRows(ids);
        Set<Long> authors = new HashSet<>();
        rows.values().stream().filter(row -> !row.hidden()).forEach(row -> authors.add(row.authorId()));
        var names = accounts.displays(authors);
        var tags = articles.tags(rows.keySet());
        Map<Long, LoadedArticle<ArticleListView>> result = new HashMap<>();
        for (long id : ids) {
            var row = rows.get(id);
            if (row == null) { result.put(id, LoadedArticle.missing()); }
            else if (row.hidden()) { result.put(id, LoadedArticle.hidden()); }
            else { result.put(id, LoadedArticle.visible(new ArticleListView(Long.toString(id), row.title(),
                    Long.toString(row.authorId()), author(names, row.authorId()), row.summary(), url(row),
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
    private static String author(Map<Long, UserDisplayView> names, long id) {
        var display = names.get(id);
        if (display == null || display.username() == null) { throw ArticleErrors.unavailable(); }
        return display.username();
    }
}
