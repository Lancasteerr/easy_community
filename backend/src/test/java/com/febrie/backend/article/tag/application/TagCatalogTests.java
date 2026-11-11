package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.tag.application.dto.TagView;
import com.febrie.backend.shared.error.ApiException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用受控版本和屏障验证SQL次数、并发补齐及依赖失败，不依靠概率时序。 */
class TagCatalogTests {
    private final TagDirectoryReader reader = mock(TagDirectoryReader.class);
    private final AtomicLong nanos = new AtomicLong(1);
    private final TagCatalog catalog = new TagCatalog(reader, nanos::get);

    private void initialize(long version, TagView... tags) {
        when(reader.load()).thenReturn(new TagDirectoryReader.Directory(version, List.of(tags)));
        catalog.checkForUpdates();
        clearInvocations(reader);
    }
    private static TagView tag(long id, String name) { return new TagView(Long.toString(id), name); }
    private static String code(Runnable operation) { return assertThrows(ApiException.class, operation::run).code(); }

    @Test void initializedSearchAndKnownIdsNeverReadSql() {
        initialize(1, tag(4, "Java Spring"), tag(3, "Spring Security"), tag(2, "Spring Boot"), tag(1, "Redis"));
        assertEquals(List.of("2", "3", "4"), catalog.search("ＳＰＲＩＮＧ", 10).stream().map(TagView::tagId).toList());
        assertEquals(List.of("2"), catalog.search("spring", 1).stream().map(TagView::tagId).toList());
        assertEquals(List.of(), catalog.search(null, 10));
        assertEquals(List.of(), catalog.search("not found", 10));
        catalog.requireAllExist(List.of());
        catalog.requireAllExist(List.of(1L, 2L));
        assertEquals("Redis", catalog.resolve(List.of(1L)).get(1L).name());
        verifyNoInteractions(reader);
    }

    @Test void rejectsInvalidSearchBoundsAndDistinguishesEmptyFromUninitialized() {
        assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.search("", 10)));
        initialize(1);
        assertEquals(List.of(), catalog.search("x", 10));
        for (int limit : List.of(0, 21)) { assertEquals("INVALID_ARTICLE", code(() -> catalog.search("x", limit))); }
        assertEquals("INVALID_ARTICLE", code(() -> catalog.search("😀".repeat(129), 10)));
        assertEquals("INVALID_ARTICLE", code(() -> catalog.search("a".repeat(33), 10)));
        verifyNoInteractions(reader);
    }

    @Test void equalVersionRejectsUnknownIdWithExactlyOneQuery() {
        initialize(7, tag(1, "Java"));
        when(reader.version()).thenReturn(7L);
        assertEquals("INVALID_ARTICLE", code(() -> catalog.requireAllExist(List.of(1L, 9L))));
        verify(reader).version();
        verifyNoMoreInteractions(reader);
        assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.resolve(List.of(9L))));
    }

    @Test void staleVersionLoadsOnceAndRechecksTheWholeSelection() {
        initialize(1, tag(1, "Java"));
        when(reader.version()).thenReturn(2L);
        doReturn(new TagDirectoryReader.Directory(2, List.of(tag(1, "Java"), tag(2, "Redis")))).when(reader).load();
        catalog.requireAllExist(List.of(1L, 2L));
        verify(reader).version(); verify(reader).load(); verifyNoMoreInteractions(reader);
        clearInvocations(reader);
        catalog.requireAllExist(List.of(2L));
        verifyNoInteractions(reader);
    }

    @Test void stillUnknownAfterRefreshIsInvalidWithoutAnotherQuery() {
        initialize(1);
        when(reader.version()).thenReturn(2L);
        when(reader.load()).thenReturn(new TagDirectoryReader.Directory(2, List.of(tag(1, "Java"))));
        assertEquals("INVALID_ARTICLE", code(() -> catalog.requireAllExist(List.of(9L))));
        verify(reader).version(); verify(reader).load(); verifyNoMoreInteractions(reader);
    }

    @Test void failedRefreshKeepsOldSearchAndUsesOneSecondCooldown() {
        initialize(1, tag(1, "Java"));
        when(reader.version()).thenReturn(2L);
        when(reader.load()).thenThrow(new IllegalStateException("SQL failure"));
        assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.requireAllExist(List.of(2L))));
        assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.requireAllExist(List.of(2L))));
        verify(reader, times(1)).load();
        assertEquals(1, catalog.search("java", 10).size());
        catalog.requireAllExist(List.of(1L));
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        doReturn(new TagDirectoryReader.Directory(2, List.of(tag(1, "Java"), tag(2, "Redis")))).when(reader).load();
        catalog.requireAllExist(List.of(2L));
        verify(reader, times(2)).load();
    }

    @Test void regressedVersionAndInsufficientSnapshotAreDependencyFailures() {
        initialize(3, tag(1, "Java"));
        when(reader.version()).thenReturn(2L);
        assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.requireAllExist(List.of(9L))));
        verify(reader, never()).load();
        when(reader.version()).thenReturn(4L);
        when(reader.load()).thenReturn(new TagDirectoryReader.Directory(3, List.of(tag(1, "Java"))));
        assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.requireAllExist(List.of(9L))));
        assertEquals(1, catalog.search("java", 10).size());
        verify(reader, times(1)).load();
    }

    @Test void sqlVersionFailureCannotBecomeInvalidArticle() {
        initialize(1);
        when(reader.version()).thenThrow(new IllegalStateException("SQL failure"));
        assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.requireAllExist(List.of(9L))));
        verify(reader, never()).load();
    }

    @Test void newerConcurrentInstallIsUsedAfterVersionCheck() throws Exception {
        initialize(1, tag(1, "Java"));
        CountDownLatch checked = new CountDownLatch(1), release = new CountDownLatch(1);
        when(reader.version()).thenAnswer(call -> {
            if (Thread.currentThread().getName().equals("tag-check")) {
                checked.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return 1L;
            }
            return 2L;
        });
        when(reader.load()).thenReturn(new TagDirectoryReader.Directory(2, List.of(tag(1, "Java"), tag(2, "Redis"))));
        try (var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "tag-check"))) {
            var validation = executor.submit(() -> catalog.requireAllExist(List.of(2L)));
            try {
                assertTrue(checked.await(5, TimeUnit.SECONDS));
                catalog.checkForUpdates();
            } finally { release.countDown(); }
            validation.get(5, TimeUnit.SECONDS);
        }
        verify(reader, times(1)).load();
    }

    @Test void concurrentFullRefreshTimesOutInsteadOfLoadingAgain() throws Exception {
        initialize(1);
        when(reader.version()).thenReturn(2L);
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        when(reader.load()).thenAnswer(call -> {
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new TagDirectoryReader.Directory(2, List.of(tag(2, "Redis")));
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var refresh = executor.submit(catalog::checkForUpdates);
            try {
                assertTrue(loading.await(5, TimeUnit.SECONDS));
                assertEquals("DEPENDENCY_UNAVAILABLE", code(() -> catalog.requireAllExist(List.of(2L))));
                verify(reader, times(1)).load();
            } finally { release.countDown(); }
            refresh.get(5, TimeUnit.SECONDS);
        }
        catalog.requireAllExist(List.of(2L));
    }
}
