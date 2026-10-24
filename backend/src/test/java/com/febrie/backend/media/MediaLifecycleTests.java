package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.domain.*;
import com.febrie.backend.shared.error.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 真实事务验证正文与封面集合、跨文章复用、失败补偿及清理竞争。 */
class MediaLifecycleTests extends MediaTestSupport {
    private MediaReferenceService references() { return new MediaReferenceService(files, clock); }
    private MediaLifecycleService lifecycle() { return new MediaLifecycleService(files, media, storage, clock, manager); }

    @Test
    void bodyOccurrencesAndCoverAreOneReferenceAndOtherArticlesRemainIndependent() {
        String id = ready(MediaPurpose.ARTICLE_IMAGE);
        var references = references();
        transaction.executeWithoutResult(status -> references.syncArticle(1001, owner, List.of(id, id, id), id));
        assertEquals(1, files.references(ReferenceType.ARTICLE, 1001).size());
        transaction.executeWithoutResult(status -> references.syncArticle(1001, owner, List.of(id), id));
        transaction.executeWithoutResult(status -> references.syncArticle(1001, owner, List.of(), id));
        assertTrue(files.hasReferences(id));
        transaction.executeWithoutResult(status -> references.syncArticle(1002, owner, List.of(id), null));
        transaction.executeWithoutResult(status -> references.syncArticle(1001, owner, List.of(), null));
        assertEquals(1, files.references(ReferenceType.ARTICLE, 1002).size());
        assertNull(files.find(id).orElseThrow().unreferencedAt());
        clock.advance(Duration.ofDays(8)); lifecycle().maintainOne(id);
        assertEquals(0, storage.deletions.get());
        transaction.executeWithoutResult(status -> references.syncArticle(1002, owner, List.of(), null));
        assertEquals(clock.instant(), files.find(id).orElseThrow().unreferencedAt());
    }

    @Test
    void rollbackRestoresAssociationAndUnreferencedTimestampTogether() {
        String id = ready(MediaPurpose.ARTICLE_IMAGE);
        var references = references();
        transaction.executeWithoutResult(status -> references.syncArticle(1001, owner, List.of(id), null));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            references.syncArticle(1001, owner, List.of(), null);
            throw new IllegalStateException("模拟文章保存失败");
        }));
        assertTrue(files.hasReferences(id)); assertNull(files.find(id).orElseThrow().unreferencedAt());
    }

    @Test
    void deleteFailureRetriesAndDeletedTombstoneRemovesLateObjects() {
        String id = ready(MediaPurpose.ARTICLE_IMAGE);
        clock.advance(Duration.ofDays(8));
        storage.deleteFailure = true;
        assertThrows(ApiException.class, () -> lifecycle().maintainOne(id));
        assertEquals(MediaStatus.DELETING, files.find(id).orElseThrow().status());
        storage.deleteFailure = false;
        clock.advance(Duration.ofSeconds(6)); lifecycle().maintainOne(id);
        assertEquals(MediaStatus.DELETED, files.find(id).orElseThrow().status());
        var file = files.find(id).orElseThrow();
        storage.objects.put(file.publicKey(), new ObjectStorageProvider.ObjectMetadata(5, "image/png", "late"));
        clock.advance(Duration.ofDays(1)); lifecycle().maintainOne(id);
        assertFalse(storage.objects.containsKey(file.publicKey()));
    }

    @Test
    void deletingStateRejectsNewBindingWhileNetworkDeletionIsInProgress() throws Exception {
        String id = ready(MediaPurpose.ARTICLE_IMAGE);
        clock.advance(Duration.ofDays(8));
        var deleting = new CountDownLatch(1); var finish = new CountDownLatch(1);
        storage.beforeDelete = () -> {
            deleting.countDown();
            try { assertTrue(finish.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var cleanup = executor.submit(() -> lifecycle().maintainOne(id));
            try {
                assertTrue(deleting.await(10, TimeUnit.SECONDS));
                assertEquals(409, assertThrows(ApiException.class, () -> transaction.executeWithoutResult(status ->
                        references().syncArticle(1001, owner, List.of(id), null))).status().value());
            } finally { finish.countDown(); }
            cleanup.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void referenceCommittedWhileCleanerWaitsForFileLockPreventsDeletion() throws Exception {
        String id = ready(MediaPurpose.ARTICLE_IMAGE);
        clock.advance(Duration.ofDays(8));
        var locked = new CountDownLatch(1); var finish = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var binding = executor.submit(() -> transaction.executeWithoutResult(status -> {
                files.lock(id).orElseThrow(); locked.countDown();
                try { assertTrue(finish.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                references().syncArticle(1001, owner, List.of(id), null);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var cleanup = executor.submit(() -> lifecycle().maintainOne(id));
            finish.countDown(); binding.get(10, TimeUnit.SECONDS); cleanup.get(10, TimeUnit.SECONDS);
        }
        assertEquals(MediaStatus.READY, files.find(id).orElseThrow().status());
        assertEquals(0, storage.deletions.get());
    }
}
