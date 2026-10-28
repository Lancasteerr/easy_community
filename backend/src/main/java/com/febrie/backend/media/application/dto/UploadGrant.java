package com.febrie.backend.media.application.dto;

import com.febrie.backend.media.application.UploadStorage.Descriptor;

/** URL在申请时确定，上传响应只提供结果，不重复提供文件身份。 */
public record UploadGrant(String mediaId, String publicUrl, Descriptor upload) { }
