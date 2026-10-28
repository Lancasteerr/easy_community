package com.febrie.backend.media.application;

import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.config.*;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import java.time.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 只回收未完成和无引用对象，不恢复审核或复制；远程删除在短事务之外。 */
@Service
public class MediaLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(MediaLifecycleService.class);
    private final FileRepository files;
    private final UploadStorage storage;
    private final MediaWorkflowProperties flow;
    private final MediaLocation location;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public MediaLifecycleService(FileRepository files, UploadStorage storage, MediaProperties p,
                                 MediaWorkflowProperties flow, Clock clock, PlatformTransactionManager manager) {
        this.files = files; this.storage = storage; this.flow = flow; this.clock = clock;
        location = new MediaLocation(p.keyPrefix());
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public void maintain() {
        for (MediaStatus state : new MediaStatus[]{MediaStatus.ISSUED, MediaStatus.READY, MediaStatus.DELETING}) {
            for (String id : files.candidates(state, clock.instant(), flow.cleanupBatchSize())) {
                try { maintainOne(id); }
                catch (RuntimeException failure) { log.warn("文件回收失败：mediaId={} exception={}", id, failure.getClass().getSimpleName()); }
            }
        }
    }
    public void maintainOne(String id) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文件回收不能位于外层业务事务中");
        }
        boolean delete = Boolean.TRUE.equals(transaction.execute(status -> {
            var file = files.lock(id).orElse(null);
            Instant now = clock.instant();
            // 重新检查当前维护时间，不能使用扫描前或绑定再解绑前的旧宽限期。
            if (file == null || file.nextActionAt() == null || file.nextActionAt().isAfter(now) || files.hasReferences(id)) { return false; }
            files.deleting(id, now);
            return true;
        }));
        if (!delete) { return; }
        try {
            storage.delete(location.publicKey(id));
        } catch (RuntimeException failure) {
            transaction.executeWithoutResult(status -> files.rescheduleDeletion(id, clock.instant().plus(flow.deleteRetryDelay())));
            throw failure;
        }
        // 删除对象成功后才删记录；SQL失败仍留DELETING，下一轮重复DELETE即可。
        transaction.executeWithoutResult(status -> files.deleteMetadata(id));
    }
}
