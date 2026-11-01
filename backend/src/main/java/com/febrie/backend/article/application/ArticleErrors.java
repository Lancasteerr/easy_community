package com.febrie.backend.article.application;

import com.febrie.backend.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** 文章边界统一使用安全错误描述，不向外暴露存储细节。 */
public final class ArticleErrors {
    private ArticleErrors() { }
    public static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARTICLE", "文章参数或正文结构不正确");
    }
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在");
    }
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ARTICLE_FORBIDDEN", "没有操作此文章的权限");
    }
    public static ApiException hidden() {
        return new ApiException(HttpStatus.CONFLICT, "ARTICLE_HIDDEN", "文章已删除且不可恢复");
    }
    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "文章服务暂不可用");
    }
    public static ApiException unknown() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ARTICLE_WRITE_RESULT_UNKNOWN", "写入结果未确认，请通过文章管理或编辑查询核对，勿自动重发");
    }
}
