package com.febrie.backend.media.application;

import com.febrie.backend.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** 统一文件错误，禁止将SDK异常、密钥或预签名地址带入响应和日志。 */
public final class MediaErrors {
    private MediaErrors() { }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "文件不存在");
    }

    public static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "MEDIA_STATE_CONFLICT", "文件尚未就绪或用途不匹配");
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "对象存储暂不可用，请稍后重试", 5);
    }
}
