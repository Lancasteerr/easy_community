package com.febrie.backend.media.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.febrie.backend.media.domain.MediaStatus;
import com.febrie.backend.media.infrastructure.persistence.entity.MediaFileEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 普通文件 CRUD 复用框架；锁定、状态条件和维护顺序在 XML 中可直接审阅。 */
public interface MediaFileMapper extends BaseMapper<MediaFileEntity> {
    MediaFileEntity lock(@Param("id") byte[] id);
    int clearReadySchedule(@Param("id") byte[] id);
    int scheduleReady(@Param("id") byte[] id, @Param("collectAt") Instant collectAt);
    List<MediaFileEntity> candidates(@Param("status") MediaStatus status, @Param("now") Instant now,
                                    @Param("limit") int limit);
    int markDeleting(@Param("id") byte[] id, @Param("now") Instant now);
    int rescheduleDeletion(@Param("id") byte[] id, @Param("next") Instant next);
    int deleteUnreferencedMetadata(@Param("id") byte[] id);
}
