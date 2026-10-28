package com.febrie.backend.media.application.dto;

/** 两种上传入口消费同一结果；阶段与错误码为稳定英文枚举。 */
public record UploadResult(boolean success, Stage stage, Code code) {
    public enum Stage { AUTH, UPLOAD, MODERATION, COPY, COMMIT, COMPLETE }
    public enum Code { SUCCESS, INVALID_CALLBACK, STORAGE_UPLOAD_FAILED, MODERATION_REJECTED,
        MODERATION_REVIEW_REQUIRED, MODERATION_FAILED, COPY_FAILED, COMMIT_FAILED, PROCESS_TIMEOUT }
    public static UploadResult completed() { return new UploadResult(true, Stage.COMPLETE, Code.SUCCESS); }
    public static UploadResult failed(Stage stage, Code code) { return new UploadResult(false, stage, code); }
}
