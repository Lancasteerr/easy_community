package com.febrie.backend.media.infrastructure.persistence.mapper;

import com.febrie.backend.media.domain.ReferenceType;
import com.febrie.backend.media.infrastructure.persistence.entity.MediaReferenceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 三列组合键明确出现在增删条件中，避免通用单主键接口误删引用。 */
public interface MediaReferenceMapper {
    boolean hasReferences(@Param("fileId") byte[] fileId);
    List<MediaReferenceEntity> references(@Param("type") ReferenceType type, @Param("entity") long entity);
    int insertReference(@Param("type") ReferenceType type, @Param("entity") long entity, @Param("fileId") byte[] fileId);
    int removeReference(@Param("type") ReferenceType type, @Param("entity") long entity, @Param("fileId") byte[] fileId);
}
