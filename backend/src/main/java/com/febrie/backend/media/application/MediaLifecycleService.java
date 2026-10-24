package com.febrie.backend.media.application;

import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.MediaRepository;
import java.time.*;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 分批恢复复制、延迟回收及复查墓碑；临时文件字节主要由存储生命周期删除。 */
@Service
public class MediaLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(MediaLifecycleService.class);
    private final MediaRepository files;
    private final MediaService media;
    private final ObjectStorageProvider storage;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MediaLifecycleService(MediaRepository files, MediaService media, ObjectStorageProvider storage,
                                 Clock clock, PlatformTransactionManager manager) {
        this.files = files; this.media = media; this.storage = storage; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void maintain() {
        Instant now = clock.instant();
        for (String id : files.maintenanceCandidates(now, now.minus(Duration.ofDays(7)), 50)) {
            try { maintainOne(id); }
            catch (Exception failure) {
                // 不打印SDK异常正文或签名，只记录可定位的文件身份和异常类型。
                log.warn("文件维护稍后重试：mediaId={}，异常类型={}", id, failure.getClass().getSimpleName());
            }
        }
    }

    public void maintainOne(String id) {
        var snapshot = files.find(id).orElse(null);
        if (snapshot == null) { return; }
        media.requireCurrentStorage(snapshot);
        if (snapshot.status() == MediaStatus.PROMOTING) {
            media.prepare(snapshot.ownerUserId(), id);
            return;
        }
        Deletion deletion = transaction.execute(status -> {
            var file = files.lock(id).orElseThrow();
            Instant now = clock.instant();
            if (file.status() == MediaStatus.ISSUED || file.status() == MediaStatus.UPLOADED) {
                if (!file.expiresAt().isAfter(now)) { files.expire(id); }
                return null;
            }
            boolean unreferenced = file.status() == MediaStatus.READY && file.unreferencedAt() != null
                    && !file.unreferencedAt().plus(Duration.ofDays(7)).isAfter(now);
            boolean recover = (file.status() == MediaStatus.DELETING || file.status() == MediaStatus.DELETED)
                    && (file.leaseUntil() == null || !file.leaseUntil().isAfter(now))
                    && (file.nextRetryAt() == null || !file.nextRetryAt().isAfter(now));
            if ((!unreferenced && !recover) || files.hasReferences(id)) { return null; }
            String token = UUID.randomUUID().toString();
            files.claimDeletion(id, token, now.plus(Duration.ofMinutes(2)));
            return new Deletion(file, token);
        });
        if (deletion == null) { return; }
        try {
            storage.delete(deletion.file().bucket(), deletion.file().publicKey());
            transaction.executeWithoutResult(status -> files.deleted(id, deletion.token(), clock.instant().plus(Duration.ofDays(1))));
        } catch (RuntimeException failure) {
            transaction.executeWithoutResult(status -> files.failedOperation(deletion.file(), deletion.token(), media.retryAt(deletion.file())));
            throw failure;
        }
    }

    private record Deletion(MediaFile file, String token) { }
}
