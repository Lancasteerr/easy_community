package com.febrie.backend.media.application;

import com.febrie.backend.media.application.dto.*;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.febrie.backend.media.infrastructure.persistence.MediaRepository;
import com.febrie.backend.shared.error.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 组织上传元数据确认和持久化；不读取文件正文，不执行预留的内容校验接口。 */
@Service
public class MediaService {
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final MediaRepository files;
    private final ObjectStorageProvider storage;
    private final MediaProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MediaService(MediaRepository files, ObjectStorageProvider storage, MediaProperties properties,
                        Clock clock, PlatformTransactionManager manager) {
        this.files = files; this.storage = storage; this.properties = properties; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public UploadGrant create(long owner, MediaPurpose purpose, String filename, String contentType, long size) {
        requireOutsideTransaction();
        if (purpose == null || filename == null || filename.isBlank() || filename.length() > 255
                || contentType == null || !EXTENSIONS.containsKey(contentType) || size <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_REQUEST", "文件名、用途、声明类型或大小无效");
        }
        if (size > purpose.maximumSize()) { throw tooLarge(); }
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        var file = new MediaFile(id, owner, purpose, filename, contentType, size, null, null,
                storage.name(), properties.bucket(), properties.keyPrefix() + "/temp/" + id,
                properties.keyPrefix() + "/public/" + id + "." + EXTENSIONS.get(contentType), MediaStatus.ISSUED,
                now.plusSeconds(300), now.plus(Duration.ofHours(24)), null, null, 0, null, null);
        transaction.executeWithoutResult(status -> files.insert(file));
        // 先登记对象位置，签名失败的记录也会正常到期，不对同一Key重发授权。
        var grant = storage.authorizeUpload(file.bucket(), file.tempKey(), contentType, file.uploadExpiresAt());
        return new UploadGrant(id, grant, file.expiresAt());
    }

    public MediaView get(long owner, String id) {
        var file = owned(owner, id);
        if (temporaryExpired(file)) {
            return new MediaView(file.id(), file.purpose(), MediaStatus.EXPIRED, file.declaredContentType(),
                    file.declaredSize(), file.actualSize(), file.expiresAt());
        }
        return MediaView.from(file);
    }

    public MediaView complete(long owner, String id) {
        requireOutsideTransaction();
        var file = owned(owner, id);
        if (temporaryExpired(file) || file.status() == MediaStatus.EXPIRED) { throw MediaErrors.expired(); }
        if (file.status() == MediaStatus.UPLOADED || file.status() == MediaStatus.READY) { return MediaView.from(file); }
        if (file.status() != MediaStatus.ISSUED) { throw MediaErrors.conflict(); }
        requireCurrentStorage(file);
        var metadata = storage.head(file.bucket(), file.tempKey()).orElseThrow(MediaErrors::notFound);
        // 外部HEAD完成后再锁记录；禁止在数据库事务中等待对象存储网络请求。
        Confirmation confirmation = transaction.execute(status -> {
            var current = files.lock(id).orElseThrow(MediaErrors::notFound);
            if (temporaryExpired(current)) { files.expire(id); return new Confirmation(null, MediaErrors.expired()); }
            if (current.status() == MediaStatus.UPLOADED || current.status() == MediaStatus.READY) {
                return new Confirmation(MediaView.from(current), null);
            }
            if (current.status() != MediaStatus.ISSUED) { throw MediaErrors.conflict(); }
            if (metadata.size() > current.purpose().maximumSize()) {
                files.reject(id, "MEDIA_TOO_LARGE"); return new Confirmation(null, tooLarge());
            }
            if (metadata.size() != current.declaredSize() || !current.declaredContentType().equals(metadata.contentType())
                    || metadata.etag() == null || metadata.etag().isBlank()) {
                files.reject(id, "MEDIA_METADATA_MISMATCH");
                return new Confirmation(null, new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "MEDIA_METADATA_MISMATCH", "文件大小或声明元数据不匹配，请重新申请上传"));
            }
            files.confirm(id, metadata.size(), metadata.etag());
            return new Confirmation(MediaView.from(files.find(id).orElseThrow()), null);
        });
        // 拒绝和到期状态先提交，再返回错误，避免抛异常把状态更新一同回滚。
        if (confirmation.error() != null) { throw confirmation.error(); }
        return confirmation.view();
    }

    /** 用户模块在自己的业务事务开始前调用；头像不能复用文章上传用途。 */
    public void prepareAvatar(long owner, String id) {
        if (owned(owner, id).purpose() != MediaPurpose.AVATAR) { throw MediaErrors.conflict(); }
        prepare(owner, id);
    }

    /** 未来文章提交前使用；同一图片被正文和封面共同使用时只持久化一次。 */
    public void prepareArticle(long owner, Collection<String> bodyIds, String coverId) {
        Set<String> ids = new TreeSet<>(bodyIds);
        if (coverId != null) { ids.add(coverId); }
        for (String id : ids) {
            var file = owned(owner, id);
            if (file.purpose() == MediaPurpose.AVATAR) { throw MediaErrors.conflict(); }
            if (id.equals(coverId) && file.declaredSize() > MediaPurpose.ARTICLE_COVER.maximumSize()) { throw tooLarge(); }
        }
        for (String id : ids) { prepare(owner, id); }
    }

    /** 持久化复用同一个目标Key；READY重试不复制，租约恢复先检查已存在的目标。 */
    public void prepare(long owner, String id) {
        requireOutsideTransaction();
        var original = owned(owner, id);
        requireCurrentStorage(original);
        Promotion promotion = transaction.execute(status -> {
            var file = files.lock(id).orElseThrow(MediaErrors::notFound);
            if (file.status() == MediaStatus.READY) { return null; }
            if (file.status() == MediaStatus.UPLOADED && temporaryExpired(file)) { throw MediaErrors.expired(); }
            if (file.status() != MediaStatus.UPLOADED && file.status() != MediaStatus.PROMOTING) { throw MediaErrors.conflict(); }
            Instant now = clock.instant();
            if (file.status() == MediaStatus.PROMOTING && ((file.leaseUntil() != null && file.leaseUntil().isAfter(now))
                    || (file.nextRetryAt() != null && file.nextRetryAt().isAfter(now)))) { throw MediaErrors.conflict(); }
            String token = UUID.randomUUID().toString();
            files.claimPromotion(id, token, now.plus(LEASE));
            return new Promotion(file, token);
        });
        if (promotion == null) { return; }
        var file = promotion.file();
        try {
            var existing = storage.head(file.bucket(), file.publicKey());
            if (existing.isEmpty()) {
                if (!clock.instant().isBefore(file.expiresAt())) {
                    transaction.executeWithoutResult(status -> {
                        var current = files.lock(id).orElseThrow();
                        if (promotion.token().equals(current.operationToken())) {
                            files.claimDeletion(id, promotion.token(), clock.instant());
                        }
                    });
                    throw MediaErrors.expired();
                }
                storage.copyToPublic(file.bucket(), file.tempKey(), file.publicKey(), file.declaredContentType(), file.etag());
                existing = storage.head(file.bucket(), file.publicKey());
            }
            if (existing.isEmpty() || existing.get().size() != file.actualSize()
                    || !file.declaredContentType().equals(existing.get().contentType())) { throw MediaErrors.unavailable(); }
            boolean applied = Boolean.TRUE.equals(transaction.execute(status -> files.ready(id, promotion.token(), clock.instant())));
            if (!applied) { throw MediaErrors.conflict(); }
        } catch (ApiException error) {
            transaction.executeWithoutResult(status -> files.failedOperation(file, promotion.token(), retryAt(file)));
            throw error;
        }
    }

    public String publicUrl(String id) {
        var file = files.find(id).orElseThrow(MediaErrors::notFound);
        requireCurrentStorage(file);
        if (file.status() != MediaStatus.READY) { throw MediaErrors.conflict(); }
        return properties.publicUrl(file.publicKey());
    }

    /** 旧记录不能随配置切换被重新解释为另一家服务商或另一个环境的对象。 */
    public void requireCurrentStorage(MediaFile file) {
        if (!file.storageProvider().equals(storage.name()) || !file.bucket().equals(properties.bucket())
                || !file.tempKey().startsWith(properties.keyPrefix() + "/temp/")
                || !file.publicKey().startsWith(properties.keyPrefix() + "/public/")) { throw MediaErrors.unavailable(); }
    }

    public Instant retryAt(MediaFile file) {
        long seconds = Math.min(3600, 5L << Math.min(file.retryCount(), 10));
        return clock.instant().plusSeconds(seconds);
    }

    private MediaFile owned(long owner, String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw MediaErrors.notFound();
        }
        return files.find(id).filter(file -> file.ownerUserId() == owner).orElseThrow(MediaErrors::notFound);
    }

    private boolean temporaryExpired(MediaFile file) {
        return (file.status() == MediaStatus.ISSUED || file.status() == MediaStatus.UPLOADED)
                && !clock.instant().isBefore(file.expiresAt());
    }

    private static ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE", "文件大小超过用途限制"); }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("上传确认和持久化准备必须在业务数据库事务外调用");
        }
    }

    private record Confirmation(MediaView view, ApiException error) { }
    private record Promotion(MediaFile file, String token) { }
}
