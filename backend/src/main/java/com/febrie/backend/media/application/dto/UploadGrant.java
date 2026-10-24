package com.febrie.backend.media.application.dto;

import com.febrie.backend.media.application.ObjectStorageProvider.UploadAuthorization;
import java.time.Instant;

/** 上传授权期限与业务期限分别返回，调用方不能把五分钟误当作临时文件保留期。 */
public record UploadGrant(String mediaId, UploadAuthorization upload, Instant expiresAt) { }
