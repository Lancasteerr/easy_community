package com.febrie.backend.media.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.febrie.backend.media.domain.FileRecord;
import com.febrie.backend.media.domain.MediaIds;
import com.febrie.backend.media.domain.MediaStatus;
import com.febrie.backend.media.domain.ReferenceType;
import com.febrie.backend.media.infrastructure.persistence.entity.MediaFileEntity;
import com.febrie.backend.media.infrastructure.persistence.mapper.MediaFileMapper;
import com.febrie.backend.media.infrastructure.persistence.mapper.MediaReferenceMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** 主库文件与引用操作；数据库实体在此转换为领域快照。 */
@Repository
public class FileRepository {
    private final MediaFileMapper files;
    private final MediaReferenceMapper references;

    public FileRepository(MediaFileMapper files, MediaReferenceMapper references) {
        this.files = files;
        this.references = references;
    }

    public void insert(FileRecord file) {
        var row = new MediaFileEntity();
        row.setId(MediaIds.bytes(file.id()));
        row.setUploaderId(file.uploaderId());
        row.setPurpose(file.purpose());
        // 新上传始终从 ISSUED 开始，不接受快照携带的其他状态或可信元数据。
        row.setStatus(MediaStatus.ISSUED);
        row.setCreatedAt(file.createdAt());
        row.setNextActionAt(file.nextActionAt());
        files.insert(row);
    }

    public Optional<FileRecord> find(String id) {
        return Optional.ofNullable(files.selectOne(Wrappers.<MediaFileEntity>lambdaQuery()
                .eq(MediaFileEntity::getId, MediaIds.bytes(id)))).map(MediaFileEntity::toRecord);
    }

    public Optional<FileRecord> lock(String id) {
        return Optional.ofNullable(files.lock(MediaIds.bytes(id))).map(MediaFileEntity::toRecord);
    }

    public boolean hasReferences(String id) { return references.hasReferences(MediaIds.bytes(id)); }

    public Set<String> references(ReferenceType type, long entity) {
        return references.references(type, entity).stream().map(row -> MediaIds.text(row.getFileId()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public void addReference(ReferenceType type, long entity, String id) {
        byte[] fileId = MediaIds.bytes(id);
        references.insertReference(type, entity, fileId);
        files.clearReadySchedule(fileId);
    }

    public void removeReference(ReferenceType type, long entity, String id) {
        references.removeReference(type, entity, MediaIds.bytes(id));
    }

    /** 调用者持有文件行锁；逐语句查询看到 RC 下其他实体最新提交的引用。 */
    public void scheduleIfUnreferenced(String id, Instant collectAt) {
        if (!hasReferences(id)) { files.scheduleReady(MediaIds.bytes(id), collectAt); }
    }

    public List<String> candidates(MediaStatus status, Instant now, int limit) {
        return files.candidates(status, now, limit).stream().map(row -> MediaIds.text(row.getId())).toList();
    }

    public void deleting(String id, Instant now) { files.markDeleting(MediaIds.bytes(id), now); }

    public void rescheduleDeletion(String id, Instant next) { files.rescheduleDeletion(MediaIds.bytes(id), next); }

    public void deleteMetadata(String id) { files.deleteUnreferencedMetadata(MediaIds.bytes(id)); }
}
