package com.febrie.backend.media.application;

import com.febrie.backend.media.application.dto.UploadGrant;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.config.*;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import com.febrie.backend.media.infrastructure.ratelimit.UploadRateLimit;
import com.febrie.backend.shared.error.ApiException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 申请只建档和签发POST凭证，通知负责发布，业务绑定不再触发存储操作。 */
@Service
public class MediaService {
    private final FileRepository files;
    private final UploadStorage storage;
    private final MediaProperties p;
    private final MediaWorkflowProperties flow;
    private final UploadRateLimit limits;
    private final Clock clock;
    public MediaService(FileRepository files, UploadStorage storage, MediaProperties p,
                        MediaWorkflowProperties flow, UploadRateLimit limits, Clock clock) {
        this.files = files; this.storage = storage; this.p = p; this.flow = flow; this.limits = limits; this.clock = clock;
    }
    public UploadGrant create(long uploader, MediaPurpose purpose, String filename, String mime, long size) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("签发不能处于外层事务中"); }
        if (uploader <= 0 || purpose == null || filename == null || filename.isBlank() || filename.length() > 255
                || !Set.of("image/jpeg", "image/png", "image/webp").contains(mime == null ? "" : mime) || size <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_REQUEST", "文件名、用途、类型或大小无效");
        }
        if (size > purpose.maximumSize()) { throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE", "文件超过用途大小限制"); }
        limits.consume(uploader);
        // 毫秒精度只取一次，数据库与签名有效期使用同一创建时间。
        Instant created = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String id = UUID.randomUUID().toString();
        files.insert(new FileRecord(id, uploader, purpose, MediaStatus.ISSUED, null, null, created, created.plus(flow.issuedTtl())));
        try {
            var location = new MediaLocation(p.keyPrefix());
            var descriptor = storage.authorize(location.tempKey(id), mime, size, created, created.plus(flow.policyTtl()));
            return new UploadGrant(id, p.publicUrl(location.publicKey(id)), descriptor);
        } catch (RuntimeException failure) { throw MediaErrors.unavailable(); }
    }
    /** 调用者已通过READY引用读取业务资料；公开URL本身不需要再次访问存储或SQL。 */
    public String publicUrl(String id) { return p.publicUrl(new MediaLocation(p.keyPrefix()).publicKey(id)); }
}
