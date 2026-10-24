package com.febrie.backend.media.application;

import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.MediaRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅供已验证业务权限的应用服务调用；关联变更必须与业务行共享事务。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class MediaReferenceService {
    private final MediaRepository files;
    private final Clock clock;

    public MediaReferenceService(MediaRepository files, Clock clock) { this.files = files; this.clock = clock; }

    /** 调用者先锁用户行并提供其当前头像ID；文件锁始终按ID排序。 */
    public void replaceAvatar(long userId, String oldId, String newId) {
        requireCommittedReads();
        Set<String> affected = new TreeSet<>();
        if (oldId != null) { affected.add(oldId); }
        if (newId != null) { affected.add(newId); }
        Map<String, MediaFile> locked = lock(affected);
        Set<String> existing = files.references(ReferenceType.USER_AVATAR, userId);
        Set<String> expected = oldId == null ? Set.of() : Set.of(oldId);
        if (!existing.equals(expected)) { throw MediaErrors.conflict(); }
        if (newId != null) {
            var file = locked.get(newId);
            requireBindable(file, userId);
            if (file.purpose() != MediaPurpose.AVATAR) { throw MediaErrors.conflict(); }
        }
        if (Objects.equals(oldId, newId)) { return; }
        if (newId == null) {
            if (oldId != null) { files.removeReference(ReferenceType.USER_AVATAR, userId, oldId); }
        } else if (oldId == null) {
            files.addReference(ReferenceType.USER_AVATAR, userId, newId);
        } else {
            files.replaceAvatar(userId, newId, clock.instant());
        }
        if (oldId != null) { files.unreferenced(oldId, clock.instant()); }
    }

    /** 正文出现次数与封面位置由文章数据表达，引用表只保存整个文章的去重集合。 */
    public void syncArticle(long articleId, long owner, Collection<String> bodyIds, String coverId) {
        requireCommittedReads();
        if (articleId <= 0) { throw new IllegalArgumentException("文章ID必须为正数"); }
        Set<String> target = new TreeSet<>(bodyIds);
        if (coverId != null) { target.add(coverId); }
        // 调用者必须先锁文章行；READ_COMMITTED避免更早查询创建的快照遗漏已提交关联。
        Set<String> existing = files.references(ReferenceType.ARTICLE, articleId);
        Set<String> affected = new TreeSet<>(existing); affected.addAll(target);
        Map<String, MediaFile> locked = lock(affected);
        for (String id : target) {
            var file = locked.get(id);
            requireBindable(file, owner);
            if (file.purpose() == MediaPurpose.AVATAR
                    || file.actualSize() > (id.equals(coverId) ? MediaPurpose.ARTICLE_COVER.maximumSize() : MediaPurpose.ARTICLE_IMAGE.maximumSize())) {
                throw MediaErrors.conflict();
            }
        }
        for (String id : target) {
            if (!existing.contains(id)) { files.addReference(ReferenceType.ARTICLE, articleId, id); }
        }
        for (String id : existing) {
            if (!target.contains(id)) {
                files.removeReference(ReferenceType.ARTICLE, articleId, id);
                files.unreferenced(id, clock.instant());
            }
        }
    }

    private Map<String, MediaFile> lock(Set<String> ids) {
        Map<String, MediaFile> result = new HashMap<>();
        for (String id : ids) { result.put(id, files.lock(id).orElseThrow(MediaErrors::notFound)); }
        return result;
    }

    private static void requireBindable(MediaFile file, long owner) {
        if (file.ownerUserId() != owner) { throw MediaErrors.notFound(); }
        if (file.status() != MediaStatus.READY || file.actualSize() == null) { throw MediaErrors.conflict(); }
    }

    private static void requireCommittedReads() {
        if (!Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("业务文件关联必须在显式READ_COMMITTED事务中维护，并先锁定业务实体");
        }
    }
}
