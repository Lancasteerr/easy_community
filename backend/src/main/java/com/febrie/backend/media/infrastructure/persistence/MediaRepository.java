package com.febrie.backend.media.infrastructure.persistence;

import com.febrie.backend.media.domain.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 显式SQL维护文件及引用；调用方先锁文件，业务字段与引用共享同一事务。 */
@Repository
public class MediaRepository {
    private static final RowMapper<MediaFile> MAPPER = (rs, row) -> new MediaFile(
            rs.getString("id"), rs.getLong("owner_user_id"), MediaPurpose.valueOf(rs.getString("purpose")),
            rs.getString("filename"), rs.getString("declared_content_type"), rs.getLong("declared_size"),
            rs.getObject("actual_size", Long.class), rs.getString("etag"), rs.getString("storage_provider"),
            rs.getString("bucket"), rs.getString("temp_key"), rs.getString("public_key"), MediaStatus.valueOf(rs.getString("status")),
            instant(rs, "upload_expires_at"), instant(rs, "expires_at"), rs.getString("operation_token"),
            instant(rs, "lease_until"), rs.getInt("retry_count"), instant(rs, "next_retry_at"), instant(rs, "unreferenced_at"));
    private final JdbcTemplate jdbc;

    public MediaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(MediaFile file) {
        jdbc.update("""
                INSERT INTO media_file(id,owner_user_id,purpose,filename,declared_content_type,declared_size,
                    storage_provider,bucket,temp_key,public_key,status,upload_expires_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, file.id(), file.ownerUserId(), file.purpose().name(), file.filename(), file.declaredContentType(),
                file.declaredSize(), file.storageProvider(), file.bucket(), file.tempKey(), file.publicKey(), file.status().name(),
                timestamp(file.uploadExpiresAt()), timestamp(file.expiresAt()));
    }

    public Optional<MediaFile> find(String id) { return query(id, false); }

    public Optional<MediaFile> lock(String id) { return query(id, true); }

    private Optional<MediaFile> query(String id, boolean lock) {
        return jdbc.query("SELECT * FROM media_file WHERE id=?" + (lock ? " FOR UPDATE" : ""), MAPPER, id).stream().findFirst();
    }

    public void confirm(String id, long size, String etag) {
        jdbc.update("UPDATE media_file SET status='UPLOADED',actual_size=?,etag=? WHERE id=? AND status='ISSUED'", size, etag, id);
    }

    public void reject(String id, String code) {
        jdbc.update("UPDATE media_file SET status='REJECTED',failure_code=? WHERE id=? AND status='ISSUED'", code, id);
    }

    public void expire(String id) {
        jdbc.update("UPDATE media_file SET status='EXPIRED' WHERE id=? AND status IN ('ISSUED','UPLOADED')", id);
    }

    /** 操作令牌用于拒绝旧工作者的数据库回写，网络操作始终在事务外。 */
    public void claimPromotion(String id, String token, Instant leaseUntil) {
        jdbc.update("""
                UPDATE media_file SET status='PROMOTING',operation_token=?,lease_until=?,next_retry_at=NULL
                WHERE id=?
                """, token, timestamp(leaseUntil), id);
    }

    public boolean ready(String id, String token, Instant now) {
        return jdbc.update("""
                UPDATE media_file SET status='READY',operation_token=NULL,lease_until=NULL,retry_count=0,
                    next_retry_at=NULL,failure_code=NULL,unreferenced_at=?
                WHERE id=? AND status='PROMOTING' AND operation_token=?
                """, timestamp(now), id, token) == 1;
    }

    public void claimDeletion(String id, String token, Instant leaseUntil) {
        jdbc.update("UPDATE media_file SET status='DELETING',operation_token=?,lease_until=?,next_retry_at=NULL WHERE id=?",
                token, timestamp(leaseUntil), id);
    }

    public void deleted(String id, String token, Instant inspectAt) {
        jdbc.update("""
                UPDATE media_file SET status='DELETED',operation_token=NULL,lease_until=NULL,retry_count=0,
                    failure_code=NULL,next_retry_at=? WHERE id=? AND status='DELETING' AND operation_token=?
                """, timestamp(inspectAt), id, token);
    }

    public void failedOperation(MediaFile file, String token, Instant retryAt) {
        jdbc.update("""
                UPDATE media_file SET retry_count=retry_count+1,next_retry_at=?,lease_until=NULL,failure_code='STORAGE_UNAVAILABLE'
                WHERE id=? AND status IN ('PROMOTING','DELETING') AND operation_token=?
                """, timestamp(retryAt), file.id(), token);
    }

    /** 普通SELECT在维护事务的READ_COMMITTED隔离下读取最新提交引用；文件行锁排斥所有绑定写入。 */
    public boolean hasReferences(String id) {
        return !jdbc.queryForList("SELECT id FROM media_reference WHERE media_id=? LIMIT 1", Long.class, id).isEmpty();
    }

    public Set<String> references(ReferenceType type, long entityId) {
        return new TreeSet<>(jdbc.queryForList("SELECT media_id FROM media_reference WHERE reference_type=? AND entity_id=?",
                String.class, type.name(), entityId));
    }

    public void addReference(ReferenceType type, long entityId, String id) {
        jdbc.update("INSERT INTO media_reference(reference_type,entity_id,media_id) VALUES (?,?,?)", type.name(), entityId, id);
        jdbc.update("UPDATE media_file SET unreferenced_at=NULL WHERE id=?", id);
    }

    /** 更新同一头像行，避免先插入新头像触发“一个用户一个当前头像”的唯一约束。 */
    public void replaceAvatar(long userId, String id, Instant now) {
        jdbc.update("UPDATE media_reference SET media_id=?,created_at=? WHERE reference_type='USER_AVATAR' AND entity_id=?",
                id, timestamp(now), userId);
        jdbc.update("UPDATE media_file SET unreferenced_at=NULL WHERE id=?", id);
    }

    public void removeReference(ReferenceType type, long entityId, String id) {
        jdbc.update("DELETE FROM media_reference WHERE reference_type=? AND entity_id=? AND media_id=?", type.name(), entityId, id);
    }

    public void unreferenced(String id, Instant now) {
        if (!hasReferences(id)) {
            jdbc.update("UPDATE media_file SET unreferenced_at=? WHERE id=? AND status='READY'", timestamp(now), id);
        }
    }

    public List<String> maintenanceCandidates(Instant now, Instant unreferencedBefore, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM media_file WHERE
                    (status IN ('ISSUED','UPLOADED') AND expires_at<=?) OR
                    (status='READY' AND unreferenced_at<=?) OR
                    (status IN ('PROMOTING','DELETING') AND (lease_until IS NULL OR lease_until<=?)
                        AND (next_retry_at IS NULL OR next_retry_at<=?)) OR
                    (status='DELETED' AND next_retry_at<=?)
                ORDER BY updated_at,id LIMIT ?
                """, String.class, timestamp(now), timestamp(unreferencedBefore), timestamp(now), timestamp(now), timestamp(now), limit);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
}
