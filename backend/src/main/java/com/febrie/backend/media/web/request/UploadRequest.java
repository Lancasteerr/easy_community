package com.febrie.backend.media.web.request;

import com.febrie.backend.media.domain.MediaPurpose;
import jakarta.validation.constraints.*;

/** 仅接收上传声明；真实类型校验接口本版尚未实现。 */
public record UploadRequest(@NotNull MediaPurpose purpose, @NotBlank @Size(max = 255) String filename,
                            @NotBlank String contentType, @Positive long size) { }
