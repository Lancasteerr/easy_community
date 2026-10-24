package com.febrie.backend.media.domain;

/** UPLOADED仅表示对象及声明元数据已确认，不表示通过了内容校验。 */
public enum MediaStatus {
    ISSUED, UPLOADED, PROMOTING, READY, DELETING, DELETED, REJECTED, EXPIRED
}
