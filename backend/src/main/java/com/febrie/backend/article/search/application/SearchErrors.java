package com.febrie.backend.article.search.application;

import com.febrie.backend.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** 区分参数错误、会话过期和依赖失败，不向客户端泄露索引或连接详情。 */
public final class SearchErrors {
    private SearchErrors() { }
    public static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "ARTICLE_SEARCH_INVALID", "搜索参数或游标无效"); }
    public static ApiException expired() { return new ApiException(HttpStatus.CONFLICT, "ARTICLE_CURSOR_EXPIRED", "浏览游标已过期，请重新开始"); }
    public static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ARTICLE_SEARCH_UNAVAILABLE", "文章检索暂时不可用"); }
}
