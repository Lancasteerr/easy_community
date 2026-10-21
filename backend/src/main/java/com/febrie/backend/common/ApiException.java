package com.febrie.backend.common;

import org.springframework.http.HttpStatus;

/** 可对外展示的业务错误，禁止携带数据库或凭证详情。 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final long retryAfter;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, 0);
    }

    public ApiException(HttpStatus status, String code, String message, long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public long retryAfter() { return retryAfter; }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请重新登录");
    }
}
