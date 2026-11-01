package com.febrie.backend.media.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.febrie.backend.media.domain.FileRecord;
import com.febrie.backend.media.domain.MediaIds;
import com.febrie.backend.media.domain.MediaPurpose;
import com.febrie.backend.media.domain.MediaStatus;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 八字段文件表映射；二进制 UUID 只在持久层存在，时间按 JDBC 的 UTC 配置读写。 */
@Getter
@Setter
@TableName("media_file")
public class MediaFileEntity {
    @TableId(type = IdType.INPUT)
    private byte[] id;
    private Long uploaderId;
    private MediaPurpose purpose;
    private MediaStatus status;
    private String mimeType;
    private Long sizeBytes;
    private Instant createdAt;
    private Instant nextActionAt;

    /** Repository 与通知执行器共用同一种快照转换，不泄漏可变数据库实体。 */
    public FileRecord toRecord() {
        return new FileRecord(MediaIds.text(id), uploaderId, purpose, status, mimeType,
                sizeBytes, createdAt, nextActionAt);
    }
}
