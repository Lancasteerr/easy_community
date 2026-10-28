package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.media.infrastructure.persistence.FileRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import org.springframework.transaction.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 扫描结果不是删除权，必须复核锁内的新调度时间；真实锁竞争由MySQL测试覆盖。 */
class ReferenceLifecycleTests {
    @Test void renewedGracePeriodPreventsDeleteAndFailuresAreRescheduled() {
        var files = mock(FileRepository.class);
        var storage = mock(UploadStorage.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Instant now = Instant.parse("2026-10-29T00:00:00Z");
        String id = UUID.randomUUID().toString();
        var service = new MediaLifecycleService(files, storage, StorageSignatureTests.properties("minio"),
                PostPolicyTests.workflow(), Clock.fixed(now, ZoneOffset.UTC), manager);
        when(files.lock(id)).thenReturn(Optional.of(new FileRecord(id, 1, MediaPurpose.AVATAR, MediaStatus.READY, null, 123L,
                now.minusSeconds(90000), now.plusSeconds(86400))));
        service.maintainOne(id);
        verifyNoInteractions(storage);
        when(files.lock(id)).thenReturn(Optional.of(new FileRecord(id, 1, MediaPurpose.AVATAR, MediaStatus.READY, null, 123L,
                now.minusSeconds(90000), now.minusSeconds(1))));
        doThrow(new IllegalStateException()).when(storage).delete(anyString());
        assertThrows(IllegalStateException.class, () -> service.maintainOne(id));
        verify(files).rescheduleDeletion(id, now.plusSeconds(300));
        verify(files, never()).deleteMetadata(anyString());
    }
}
