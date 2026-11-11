package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.dto.*;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.article.tag.application.TagCatalog;
import com.febrie.backend.article.tag.application.dto.TagView;
import com.febrie.backend.user.application.UserBasicQueryService;
import com.febrie.backend.user.application.dto.UserBasicData;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 所有文章缓存锁释放后才组装作者、标签和地址，最终响应绝不回写共享缓存。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleDisplayAssembler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ArticleDisplayAssembler.class);
    private final UserBasicQueryService users;
    private final MediaUrls urls;
    private final TagCatalog tags;
    public ArticleDisplayAssembler(UserBasicQueryService users, MediaUrls urls, TagCatalog tags) {
        this.users = users; this.urls = urls; this.tags = tags;
    }

    public List<ArticleListView> lists(List<ArticleBriefData> articles) {
        if (articles.isEmpty()) { return List.of(); }
        try {
            var authors = users.get(articles.stream().map(a -> Long.parseLong(a.authorId())).distinct().toList());
            // 一页只解析一次目录，避免各文章组装时跨越不同快照。
            var directory = tags.resolve(articles.stream().flatMap(a -> a.tagIds().stream()).distinct().toList());
            return articles.stream().map(a -> {
                var author = requireAuthor(authors.get(Long.parseLong(a.authorId())));
                return new ArticleListView(a.articleId(), a.title(), a.authorId(), author.username(),
                        a.summary(), url(a.coverMediaId()), tagViews(a.tagIds(), directory), a.publishedAt(), a.updatedAt(), url(author.avatarMediaId()));
            }).toList();
        } catch (RuntimeException error) {
            log.warn("文章列表展示组装失败 error={}", error.getClass().getSimpleName());
            throw ArticleErrors.unavailable();
        }
    }

    public ArticleDetailView detail(ArticleDetailData a) {
        try {
            var author = requireAuthor(users.get(Long.parseLong(a.authorId())).orElse(null));
            var directory = tags.resolve(a.tagIds());
            return new ArticleDetailView(a.articleId(), a.title(), a.authorId(), author.username(), a.summary(),
                    a.coverMediaId(), url(a.coverMediaId()), tagViews(a.tagIds(), directory), a.publishedAt(), a.updatedAt(), a.contentHtml(),
                    url(author.avatarMediaId()));
        } catch (RuntimeException error) {
            log.warn("文章详情展示组装失败 articleId={} error={}", a.articleId(), error.getClass().getSimpleName());
            throw ArticleErrors.unavailable();
        }
    }

    /** 编辑权限由Reader确认；返回代理后才可能触发标签目录同步。 */
    public ArticleEditView edit(ArticleEditData a) {
        var directory = tags.resolve(a.tagIds());
        return new ArticleEditView(a.articleId(), a.title(), a.content(), a.coverMediaId(), url(a.coverMediaId()),
                tagViews(a.tagIds(), directory), a.createdAt(), a.updatedAt(), a.publishedAt());
    }

    private static List<TagView> tagViews(List<Long> ids, Map<Long, TagView> directory) {
        return ids.stream().map(id -> {
            var tag = directory.get(id);
            if (tag == null) { throw ArticleErrors.unavailable(); }
            return tag;
        }).toList();
    }

    private String url(String id) { return id == null ? null : urls.publicUrl(id); }
    private static UserBasicData requireAuthor(UserBasicData author) {
        if (author == null || author.username() == null) { throw ArticleErrors.unavailable(); }
        return author;
    }
}
