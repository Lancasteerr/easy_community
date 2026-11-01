package com.febrie.backend.media.infrastructure.persistence.notification;

import com.febrie.backend.media.infrastructure.persistence.entity.MediaFileEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/** 只由通知私有执行器注册，禁止放入主库 Mapper 扫描包。 */
public interface NotificationFileMapper {
    MediaFileEntity find(@Param("id") byte[] id);
    int ready(@Param("id") byte[] id, @Param("size") long size, @Param("mime") String mime,
              @Param("now") Instant now, @Param("collectAt") Instant collectAt);
}
