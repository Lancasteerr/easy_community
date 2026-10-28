package com.febrie.backend.media.application;

/** 只能由已认证的存储通知构造；声明类型不等同于真实格式结论。 */
public record UploadedObject(String bucket, String key, long size, String declaredContentType, String etag) { }
