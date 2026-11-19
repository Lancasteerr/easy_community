package com.febrie.backend.article.like;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.ArticleListHydrator;
import com.febrie.backend.article.like.application.LikeProjection;
import com.febrie.backend.article.like.application.LikeWriteService;
import com.febrie.backend.article.like.domain.LikeCommand;
import com.febrie.backend.article.like.infrastructure.messaging.LikePublisher;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.febrie.backend.article.like.infrastructure.persistence.LikeRepository;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.shared.ratelimit.FixedWindowCounter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证受理前的可见性门槛与成功入队后的投影边界。 */
class LikeWriteServiceTests {
    private final LikeRepository repository = mock(LikeRepository.class);
    private final ArticleListHydrator visibility = mock(ArticleListHydrator.class);
    private final LikeVersionAllocator allocator = mock(LikeVersionAllocator.class);
    private final LikePublisher publisher = mock(LikePublisher.class);
    private final LikeProjection projection = mock(LikeProjection.class);
    private final FixedWindowCounter limits = mock(FixedWindowCounter.class);
    private final LikeTelemetry telemetry = mock(LikeTelemetry.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T00:00:05Z"), ZoneOffset.UTC);

    @Test void reusedOperationIdAllocatesNewVersionAndPendingFailureIsHarmless() {
        when(repository.publicArticle(2)).thenReturn(true);
        when(allocator.next()).thenReturn(10L, 11L);
        doThrow(new IllegalStateException()).when(projection).pending(any());
        var service = service(false);
        String operation = UUID.randomUUID().toString();

        assertEquals("10", service.set(1, 2, true, operation).version());
        assertEquals("11", service.set(1, 2, false, operation).version());

        var commands = ArgumentCaptor.forClass(LikeCommand.class);
        verify(publisher, times(2)).publish(commands.capture());
        verify(repository, times(2)).publicArticle(2);
        verifyNoInteractions(visibility);
        assertEquals(operation, commands.getAllValues().get(1).operationId());
    }

    @Test void rateLimitPrecedesVersionAllocationAndInvalidOperationIsRejected() {
        var service = service(true);
        assertEquals(400, assertThrows(ApiException.class, () -> service.set(1, 2, true, "bad")).status().value());
        verifyNoInteractions(visibility, repository);

        when(visibility.isPublic(2)).thenReturn(true);
        when(limits.increment(anyString(), any())).thenReturn(61L);
        var limited = assertThrows(ApiException.class, () -> service.set(1, 2, true, UUID.randomUUID().toString()));
        assertEquals(429, limited.status().value());
        assertEquals(55, limited.retryAfter());
        verifyNoInteractions(allocator, publisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cacheEnabledChecksVisibilityBeforePublishingForBothCommands(boolean desired) {
        when(visibility.isPublic(2)).thenReturn(true);
        when(allocator.next()).thenReturn(10L);

        var accepted = service(true).set(1, 2, desired, UUID.randomUUID().toString());

        assertTrue(accepted.accepted());
        assertEquals(desired, accepted.liked());
        var command = ArgumentCaptor.forClass(LikeCommand.class);
        var order = inOrder(visibility, allocator, publisher);
        order.verify(visibility).isPublic(2);
        order.verify(allocator).next();
        order.verify(publisher).publish(command.capture());
        assertEquals(desired, command.getValue().desiredLiked());
        verifyNoInteractions(repository);
    }

    @Test void hiddenMissingAndUnavailableArticlesNeverReachThePublisher() {
        when(visibility.isPublic(2)).thenReturn(false);
        when(visibility.isPublic(3)).thenReturn(false);
        when(visibility.isPublic(4)).thenThrow(ArticleErrors.unavailable());
        var service = service(true);

        assertEquals(404, assertThrows(ApiException.class, () -> service.set(1, 2, true, UUID.randomUUID().toString())).status().value());
        assertEquals(404, assertThrows(ApiException.class, () -> service.set(1, 3, false, UUID.randomUUID().toString())).status().value());
        assertEquals(503, assertThrows(ApiException.class, () -> service.set(1, 4, true, UUID.randomUUID().toString())).status().value());
        verifyNoInteractions(repository, limits, allocator, publisher, projection);
    }

    private LikeWriteService service(boolean visibilityCacheEnabled) {
        return new LikeWriteService(LikePublisherTests.properties(), repository, visibility, allocator,
                publisher, projection, limits, clock, telemetry, visibilityCacheEnabled);
    }
}
