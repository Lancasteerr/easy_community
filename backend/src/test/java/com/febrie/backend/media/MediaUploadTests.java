package com.febrie.backend.media;

import com.febrie.backend.media.application.ObjectStorageProvider.ObjectMetadata;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.shared.error.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证元数据确认、到期和复制恢复；不依赖内容解码或临时快照。 */
class MediaUploadTests extends MediaTestSupport {
    @Test
    void uploadGrantExpiresBeforeBusinessSlotAndCompletedMetadataIsIdempotent() {
        var grant = media.create(owner, MediaPurpose.AVATAR, "头像.png", "image/png", 5);
        assertEquals(clock.instant().plusSeconds(300), grant.upload().expiresAt());
        assertEquals(clock.instant().plusSeconds(86400), grant.expiresAt());
        var file = files.find(grant.mediaId()).orElseThrow();
        storage.objects.put(file.tempKey(), new ObjectMetadata(5, "image/png", "opaque"));
        clock.advance(Duration.ofMinutes(6));
        assertEquals(MediaStatus.UPLOADED, media.complete(owner, file.id()).status());
        assertEquals(MediaStatus.UPLOADED, media.complete(owner, file.id()).status());
        assertEquals(404, assertThrows(ApiException.class, () -> media.get(owner + 1, file.id())).status().value());
        media.prepareAvatar(owner, file.id());
        assertTrue(storage.objects.containsKey(file.tempKey()));
        clock.advance(Duration.ofDays(2));
        media.prepareAvatar(owner, file.id());
        assertEquals(1, storage.copies.get());
    }

    @Test
    void expiredTemporaryFileCannotBeConfirmedOrPromoted() {
        String id = uploaded(MediaPurpose.AVATAR);
        clock.advance(Duration.ofHours(24));
        assertEquals(410, assertThrows(ApiException.class, () -> media.complete(owner, id)).status().value());
        assertEquals(410, assertThrows(ApiException.class, () -> media.prepareAvatar(owner, id)).status().value());
        assertEquals(MediaStatus.EXPIRED, media.get(owner, id).status());
        assertEquals(0, storage.copies.get());
    }

    @Test
    void mismatchedMetadataIsRejectedAndRejectionIsCommitted() {
        var grant = media.create(owner, MediaPurpose.AVATAR, "test.png", "image/png", 5);
        var file = files.find(grant.mediaId()).orElseThrow();
        storage.objects.put(file.tempKey(), new ObjectMetadata(6, "image/png", "opaque"));
        assertEquals(422, assertThrows(ApiException.class, () -> media.complete(owner, file.id())).status().value());
        assertEquals(MediaStatus.REJECTED, files.find(file.id()).orElseThrow().status());
        assertEquals(400, assertThrows(ApiException.class, () -> media.create(owner, MediaPurpose.AVATAR, "a.svg", "image/svg+xml", 5)).status().value());
        assertEquals(413, assertThrows(ApiException.class, () -> media.create(owner, MediaPurpose.AVATAR, "a.png", "image/png", 3 * 1024 * 1024)).status().value());
    }

    @Test
    void lostCopyResponseIsRecoveredByInspectingTargetWithoutAnotherCopy() {
        String id = uploaded(MediaPurpose.AVATAR);
        storage.copyThenFail = true;
        assertEquals(503, assertThrows(ApiException.class, () -> media.prepareAvatar(owner, id)).status().value());
        assertEquals(MediaStatus.PROMOTING, files.find(id).orElseThrow().status());
        clock.advance(Duration.ofSeconds(6));
        media.prepareAvatar(owner, id);
        assertEquals(MediaStatus.READY, files.find(id).orElseThrow().status());
        assertEquals(1, storage.copies.get());
    }

    @Test
    void avatarRequiresCorrectPurposeAndNetworkWorkCannotRunInsideBusinessTransaction() {
        String articleImage = uploaded(MediaPurpose.ARTICLE_IMAGE);
        assertEquals(409, assertThrows(ApiException.class, () -> media.prepareAvatar(owner, articleImage)).status().value());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> media.complete(owner, articleImage)));
    }
}
