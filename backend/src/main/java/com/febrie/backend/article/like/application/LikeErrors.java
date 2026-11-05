package com.febrie.backend.article.like.application;

import com.febrie.backend.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** 明确区分未发送、已拒绝和未知结果，不能把网络故障解释成业务回滚。 */
public final class LikeErrors {
    private LikeErrors() { }
    public static ApiException invalid() { return error(HttpStatus.BAD_REQUEST, "INVALID_LIKE_REQUEST", "点赞参数或操作标识无效"); }
    public static ApiException unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "点赞信息暂不可用"); }
    public static ApiException allocator() { return error(HttpStatus.SERVICE_UNAVAILABLE, "LIKE_ALLOCATOR_UNAVAILABLE", "点赞签发暂停，请稍后重试"); }
    public static ApiException publishUnavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "LIKE_PUBLISH_UNAVAILABLE", "点赞命令尚未发送，请稍后重试"); }
    public static ApiException rejected() { return error(HttpStatus.SERVICE_UNAVAILABLE, "LIKE_PUBLISH_REJECTED", "点赞命令未能路由，请稍后重试"); }
    public static ApiException unknown() { return error(HttpStatus.SERVICE_UNAVAILABLE, "LIKE_WRITE_RESULT_UNKNOWN", "点赞结果未确认，操作可能已执行，请勿无限重发旧操作"); }
    private static ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}
