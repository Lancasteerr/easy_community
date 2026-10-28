package com.febrie.backend.media.application;

import com.febrie.backend.media.application.dto.UploadResult;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.config.*;
import com.febrie.backend.media.infrastructure.persistence.NotificationFiles;
import java.time.Clock;
import java.util.Set;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import static com.febrie.backend.media.application.dto.UploadResult.*;

/** 已认证通知的直线发布流程；无领取、重试、补偿或持锁网络调用。 */
@Service
public class FinishUpload {
    private static final Logger log = LoggerFactory.getLogger(FinishUpload.class);
    private final NotificationFiles files;
    private final UploadStorage storage;
    private final ContentModeration moderation;
    private final MediaProperties properties;
    private final MediaWorkflowProperties flow;
    private final Clock clock;
    public FinishUpload(NotificationFiles files, UploadStorage storage, ContentModeration moderation,
                        MediaProperties properties, MediaWorkflowProperties flow, Clock clock) {
        this.files = files; this.storage = storage; this.moderation = moderation;
        this.properties = properties; this.flow = flow; this.clock = clock;
    }
    public UploadResult finishUpload(UploadedObject object, ProcessingDeadline deadline) {
        Stage stage = Stage.AUTH;
        String id = null;
        UploadResult result;
        try {
            deadline.check();
            var location = new MediaLocation(properties.keyPrefix());
            id = location.idFromTempKey(object.key());
            if (!properties.bucket().equals(object.bucket()) || object.size() <= 0
                    || !Set.of("image/jpeg", "image/png", "image/webp").contains(object.declaredContentType() == null ? "" : object.declaredContentType())
                    || object.etag() == null || object.etag().isBlank()) { throw new IllegalArgumentException("通知元数据无效"); }
            var file = files.find(id, deadline).orElseThrow(() -> new IllegalArgumentException("通知文件不存在"));
            if (file.status() != MediaStatus.ISSUED || !clock.instant().isBefore(file.createdAt().plus(flow.notificationTtl()))
                    || file.nextActionAt() == null || !clock.instant().isBefore(file.nextActionAt())
                    || object.size() > file.purpose().maximumSize()) { throw new IllegalArgumentException("通知状态或期限无效"); }
            stage = Stage.MODERATION;
            deadline.stage(stage);
            deadline.remainingMillis(ProcessingDeadline.COMMIT_RESERVE_MS);
            var decision = moderation.review(object, deadline);
            deadline.check();
            if (decision != ContentModeration.Decision.PASS) {
                result = failed(stage, decision == ContentModeration.Decision.REJECT ? Code.MODERATION_REJECTED
                        : decision == ContentModeration.Decision.REVIEW_REQUIRED ? Code.MODERATION_REVIEW_REQUIRED : Code.MODERATION_FAILED);
            } else {
                stage = Stage.COPY;
                deadline.stage(stage);
                deadline.remainingMillis(ProcessingDeadline.COMMIT_RESERVE_MS);
                storage.copy(object, location.publicKey(id), deadline);
                deadline.check();
                stage = Stage.COMMIT;
                deadline.stage(stage);
                var now = clock.instant();
                // 目前两个审核适配器均不提供真实格式结论，不能把声明MIME填成已验证结果。
                boolean updated = files.ready(id, object.size(), null, now, now.plus(flow.unreferencedTtl()), deadline);
                deadline.check();
                result = updated ? completed() : failed(stage, Code.COMMIT_FAILED);
            }
        } catch (ProcessingDeadline.TimedOut timeout) { result = failed(stage, Code.PROCESS_TIMEOUT); }
        catch (Exception failure) {
            Code code = switch (stage) {
                case AUTH -> Code.INVALID_CALLBACK;
                case MODERATION -> Code.MODERATION_FAILED;
                case COPY -> Code.COPY_FAILED;
                default -> Code.COMMIT_FAILED;
            };
            // 网络操作可能在返回异常前已经耗尽预算，优先报告真实的截止时间结果。
            try { deadline.check(); } catch (ProcessingDeadline.TimedOut timeout) { code = Code.PROCESS_TIMEOUT; }
            result = failed(stage, code);
        }
        log.info("文件通知完成：mediaId={} stage={} code={} elapsedMs={}", id, result.stage(), result.code(), deadline.elapsedMillis());
        return result;
    }
}
