package com.febrie.backend.article.comment.application;

import com.febrie.backend.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** 对外仅暴露稳定的评论错误码。 */
public final class CommentErrors {
    private CommentErrors() { }

    public static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "COMMENT_INVALID", "评论请求、正文或游标无效");
    }

    public static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "评论或所属文章不存在");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "COMMENT_FORBIDDEN", "无权删除该评论");
    }

    public static ApiException operationConflict() {
        return new ApiException(HttpStatus.CONFLICT, "COMMENT_OPERATION_CONFLICT", "同一操作 ID 已用于其他评论请求");
    }

    public static ApiException expired() {
        return new ApiException(HttpStatus.CONFLICT, "COMMENT_CURSOR_EXPIRED", "评论游标已过期，请重新开始");
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COMMENT_UNAVAILABLE", "评论暂不可用，请稍后重试", 1);
    }
}
