package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.application.dto.UploadResult.*;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.NotificationFiles;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 可控单调时钟验证迟到审核不能发布，替身只验证流程，不冒充云端契约。 */
class FinishUploadTests {
    private final NotificationFiles files = mock(NotificationFiles.class);
    private final UploadStorage storage = mock(UploadStorage.class);
    private final ContentModeration moderation = mock(ContentModeration.class);
    private final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    private final String id = UUID.randomUUID().toString();
    private final AtomicLong nanos = new AtomicLong();
    private final ProcessingDeadline deadline = new ProcessingDeadline(Duration.ofMillis(4500), nanos::get);
    private final UploadedObject object = new UploadedObject("media-test", "ec/test/temp/" + id, 123, "image/png", "etag");

    private FinishUpload service() {
        when(files.find(eq(id), any())).thenReturn(Optional.of(new FileRecord(id, 1, MediaPurpose.AVATAR,
                MediaStatus.ISSUED, null, null, now, now.plusSeconds(7200))));
        return new FinishUpload(files, storage, moderation, StorageSignatureTests.properties("minio"),
                PostPolicyTests.workflow(), Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void successRequiresCopyAndCommittedUpdate() {
        var service = service();
        when(moderation.review(any(), any())).thenReturn(ContentModeration.Decision.PASS);
        when(files.ready(anyString(), anyLong(), isNull(), any(), any(), any())).thenReturn(true);
        assertTrue(service.finishUpload(object, deadline).success());
        var order = inOrder(moderation, storage, files);
        order.verify(moderation).review(object, deadline);
        order.verify(storage).copy(object, "ec/test/public/" + id, deadline);
        order.verify(files).ready(eq(id), eq(123L), isNull(), eq(now), eq(now.plusSeconds(86400)), eq(deadline));
    }
    @Test void rejectionAndLatePassNeverCopy() {
        var service = service();
        when(moderation.review(any(), any())).thenReturn(ContentModeration.Decision.REJECT);
        assertEquals(Code.MODERATION_REJECTED, service.finishUpload(object, deadline).code());
        when(moderation.review(any(), any())).thenAnswer(call -> { nanos.set(4_600_000_000L); return ContentModeration.Decision.PASS; });
        assertEquals(Code.PROCESS_TIMEOUT, service.finishUpload(object, deadline).code());
        verifyNoInteractions(storage);
        verify(files, never()).ready(anyString(), anyLong(), any(), any(), any(), any());
    }
    @Test void copyFailureAndZeroUpdateCannotSucceed() {
        var service = service();
        when(moderation.review(any(), any())).thenReturn(ContentModeration.Decision.PASS);
        doThrow(new IllegalStateException("secret SDK message")).when(storage).copy(any(), anyString(), any());
        assertEquals(Code.COPY_FAILED, service.finishUpload(object, deadline).code());
        verify(files, never()).ready(anyString(), anyLong(), any(), any(), any(), any());
        reset(storage);
        assertEquals(Code.COMMIT_FAILED, service.finishUpload(object, deadline).code());
    }
    @Test void committedOrDeletingFilesAndReviewRequiredCannotPublish() {
        var service = service();
        when(moderation.review(any(), any())).thenReturn(ContentModeration.Decision.REVIEW_REQUIRED);
        assertEquals(Code.MODERATION_REVIEW_REQUIRED, service.finishUpload(object, deadline).code());
        for (var state : new MediaStatus[]{MediaStatus.READY, MediaStatus.DELETING}) {
            when(files.find(eq(id), any())).thenReturn(Optional.of(new FileRecord(id, 1, MediaPurpose.AVATAR, state, null, 123L, now, now.plusSeconds(86400))));
            assertEquals(Code.INVALID_CALLBACK, service.finishUpload(object, deadline).code());
        }
        verifyNoInteractions(storage);
    }
    @Test void commitExceptionAndRemainingBudgetCannotTurnIntoSuccess() {
        var service = service();
        when(moderation.review(any(), any())).thenReturn(ContentModeration.Decision.PASS);
        when(files.ready(anyString(), anyLong(), isNull(), any(), any(), any())).thenThrow(new IllegalStateException("SQL"));
        assertEquals(Code.COMMIT_FAILED, service.finishUpload(object, deadline).code());
        reset(storage);
        when(moderation.review(any(), any())).thenAnswer(call -> { nanos.set(3_300_000_000L); return ContentModeration.Decision.PASS; });
        assertEquals(Code.PROCESS_TIMEOUT, service.finishUpload(object, deadline).code());
        verifyNoInteractions(storage);
    }
}
