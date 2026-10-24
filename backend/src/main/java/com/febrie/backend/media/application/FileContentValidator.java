package com.febrie.backend.media.application;

import com.febrie.backend.media.domain.MediaPurpose;

/** 未来内容校验的顶层扩展点；V1不提供实现、不注入流程，也不伪造通过结果。 */
public interface FileContentValidator {
    ValidationResult validate(StoredObject object, MediaPurpose purpose);

    /** 使用对象定位而不是强制回传文件字节，兼容服务商侧校验方案。 */
    record StoredObject(String provider, String bucket, String key) { }

    /** 拒绝原因仅使用可审计的错误码，不透传外部服务错误正文。 */
    record ValidationResult(boolean accepted, String failureCode) { }
}
