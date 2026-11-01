package com.febrie.backend.media.infrastructure.persistence.entity;

import com.febrie.backend.media.domain.ReferenceType;
import lombok.Getter;
import lombok.Setter;

/** 引用使用三列组合主键，由专用 Mapper 操作，不声明虚假的单列 TableId。 */
@Getter
@Setter
public class MediaReferenceEntity {
    private byte[] fileId;
    private ReferenceType refType;
    private Long refId;
}
