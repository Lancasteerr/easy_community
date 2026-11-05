package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.LikeCommand;
import com.febrie.backend.article.like.infrastructure.*;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.shared.ratelimit.FixedWindowCounter;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不提供HTTP去重；已确认入队后的投影失败不能改变202结果。 */
class LikeWriteServiceTests {
    @Test void reusedOperationIdAllocatesNewVersionAndPendingFailureIsHarmless() {
        var repository=mock(LikeRepository.class);var allocator=mock(LikeVersionAllocator.class);
        var publisher=mock(LikePublisher.class);var projection=mock(LikeProjection.class);var limits=mock(FixedWindowCounter.class);
        when(repository.publicArticle(2)).thenReturn(true);when(allocator.next()).thenReturn(10L,11L);
        doThrow(new IllegalStateException()).when(projection).pending(any());
        var service=new LikeWriteService(LikePublisherTests.properties(),repository,allocator,publisher,projection,limits,Clock.systemUTC(),mock(LikeTelemetry.class));
        String operation=UUID.randomUUID().toString();
        assertEquals("10",service.set(1,2,true,operation).version());
        assertEquals("11",service.set(1,2,false,operation).version());
        var commands=ArgumentCaptor.forClass(LikeCommand.class);verify(publisher,times(2)).publish(commands.capture());
        assertEquals(operation,commands.getAllValues().get(1).operationId());
    }
    @Test void rateLimitPrecedesVersionAllocationAndInvalidOperationIsRejected() {
        var repository=mock(LikeRepository.class);var allocator=mock(LikeVersionAllocator.class);var limits=mock(FixedWindowCounter.class);
        when(repository.publicArticle(2)).thenReturn(true);when(limits.increment(anyString(),any())).thenReturn(61L);
        var service=new LikeWriteService(LikePublisherTests.properties(),repository,allocator,mock(LikePublisher.class),mock(LikeProjection.class),limits,
                Clock.fixed(Instant.parse("2026-11-05T00:00:05Z"),ZoneOffset.UTC),mock(LikeTelemetry.class));
        assertEquals(400,assertThrows(ApiException.class,()->service.set(1,2,true,"bad")).status().value());
        var limited=assertThrows(ApiException.class,()->service.set(1,2,true,UUID.randomUUID().toString()));
        assertEquals(429,limited.status().value());assertEquals(55,limited.retryAfter());verifyNoInteractions(allocator);
    }
}
