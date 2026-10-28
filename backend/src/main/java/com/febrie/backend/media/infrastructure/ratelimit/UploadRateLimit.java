package com.febrie.backend.media.infrastructure.ratelimit;

import com.febrie.backend.media.infrastructure.config.MediaWorkflowProperties;
import com.febrie.backend.shared.ratelimit.FixedWindowCounter;
import com.febrie.backend.shared.error.ApiException;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 按稳定用户ID共享签发额度；额度只限制频率，不声称是用户存储配额。 */
@Component
public class UploadRateLimit {
    private final FixedWindowCounter counter;
    private final MediaWorkflowProperties properties;
    private final Clock clock;
    public UploadRateLimit(FixedWindowCounter counter, MediaWorkflowProperties properties, Clock clock) {
        this.counter = counter; this.properties = properties; this.clock = clock;
    }
    public void consume(long uploader) {
        long second = clock.instant().getEpochSecond();
        long remaining = 60 - Math.floorMod(second, 60);
        String key = properties.redisPrefix() + ":media:uploads:" + uploader + ":" + Math.floorDiv(second, 60);
        if (counter.increment(key, Duration.ofSeconds(remaining + 60)) > properties.uploadsPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "上传申请过于频繁，请稍后重试", remaining);
        }
    }
}
