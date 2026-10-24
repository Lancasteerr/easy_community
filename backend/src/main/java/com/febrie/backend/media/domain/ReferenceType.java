package com.febrie.backend.media.domain;

/** 关联按实体去重；ARTICLE统一包含正文和封面，不按出现位置建立多行。 */
public enum ReferenceType {
    USER_AVATAR, ARTICLE
}
