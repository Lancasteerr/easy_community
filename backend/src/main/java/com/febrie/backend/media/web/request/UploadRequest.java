package com.febrie.backend.media.web.request;

import com.febrie.backend.media.domain.MediaPurpose;
import jakarta.validation.constraints.*;

/** 仅接收本次Policy声明；生产内容审核不等同于三种真实格式识别。 */
public record UploadRequest(@NotNull MediaPurpose purpose, @NotBlank @Size(max = 255) String filename,
                            @NotBlank String contentType, @Positive long size) { }
