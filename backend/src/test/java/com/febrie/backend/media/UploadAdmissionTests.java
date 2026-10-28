package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import com.febrie.backend.media.infrastructure.ratelimit.UploadRateLimit;
import com.febrie.backend.shared.error.ApiException;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 入场失败不能留下SQL记录或生成存储凭证，大小校验只做业务所需的检查。 */
class UploadAdmissionTests {
    @Test void limitIsBeforeInsertAndSigningAndDeclarationsDoNotBecomeTrustedMetadata() {
        var files = mock(FileRepository.class);
        var storage = mock(UploadStorage.class);
        var limits = mock(UploadRateLimit.class);
        var service = new MediaService(files, storage, StorageSignatureTests.properties("minio"), PostPolicyTests.workflow(),
                limits, Clock.fixed(Instant.parse("2026-10-29T00:00:00.123456Z"), ZoneOffset.UTC));
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "test", 30)).when(limits).consume(1);
        assertThrows(ApiException.class, () -> service.create(1, MediaPurpose.AVATAR, "a.png", "image/png", 10));
        verifyNoInteractions(files, storage);
        assertThrows(ApiException.class, () -> service.create(1, MediaPurpose.AVATAR, "a.png", "image/svg+xml", 10));
        assertThrows(ApiException.class, () -> service.create(1, MediaPurpose.AVATAR, "a.png", "image/png", 2L * 1024 * 1024 + 1));
        reset(limits);
        when(storage.authorize(anyString(), anyString(), anyLong(), any(), any())).thenReturn(
                new UploadStorage.Descriptor("POST", "http://upload.example.com", java.util.Map.of(), Instant.now()));
        var grant = service.create(1, MediaPurpose.AVATAR, "a.png", "image/png", 10);
        assertTrue(grant.publicUrl().endsWith("/public/" + grant.mediaId()));
        verify(files).insert(argThat(file -> file.mimeType() == null && file.sizeBytes() == null
                && file.createdAt().equals(Instant.parse("2026-10-29T00:00:00.123Z"))));
    }
}
