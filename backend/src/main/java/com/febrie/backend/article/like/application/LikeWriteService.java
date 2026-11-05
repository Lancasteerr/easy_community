package com.febrie.backend.article.like.application;

import com.febrie.backend.article.application.ArticleErrors;
import com.febrie.backend.article.like.domain.LikeCommand;
import com.febrie.backend.article.like.infrastructure.*;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.shared.ratelimit.FixedWindowCounter;
import java.time.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** API只签发并可靠发布，不写点赞SQL；202之后投影失败不能改报未受理。 */
@Service
@ConditionalOnProperty(name="app.like.enabled",havingValue="true")
@Transactional(propagation=Propagation.NEVER)
public class LikeWriteService {
    private static final Logger log=LoggerFactory.getLogger(LikeWriteService.class);
    private final LikeProperties properties;
    private final LikeRepository repository;
    private final LikeVersionAllocator allocator;
    private final LikePublisher publisher;
    private final LikeProjection projection;
    private final FixedWindowCounter windows;
    private final Clock clock;
    private final LikeTelemetry telemetry;
    public LikeWriteService(LikeProperties properties,LikeRepository repository,LikeVersionAllocator allocator,
            LikePublisher publisher,LikeProjection projection,FixedWindowCounter windows,Clock clock,LikeTelemetry telemetry) {
        this.properties=properties;this.repository=repository;this.allocator=allocator;this.publisher=publisher;
        this.projection=projection;this.windows=windows;this.clock=clock;this.telemetry=telemetry;
    }
    public Accepted set(long user,long article,boolean desired,String operationId) {
        String operation;
        try {operation=LikeCommand.operation(operationId);} catch(IllegalArgumentException invalid){throw LikeErrors.invalid();}
        if(!properties.writeEnabled()){throw LikeErrors.allocator();}
        long started=System.nanoTime();
        try {
            if(!repository.publicArticle(article)){throw ArticleErrors.notFound();}
            long second=clock.instant().getEpochSecond(),remaining=60-second%60;
            long consumed;
            try {consumed=windows.increment(properties.key("limit:"+user+":"+(second/60)),Duration.ofSeconds(remaining+60));}
            catch(RuntimeException failure){throw LikeErrors.unavailable();}
            if(consumed>properties.perMinute()){
                throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","点赞过于频繁，请稍后重试",remaining);
            }
            long version=allocator.next();
            var command=new LikeCommand(1,operation,user,article,desired,Long.toString(version),clock.instant());
            publisher.publish(command);
            try {projection.pending(command);}
            catch(RuntimeException unavailable){telemetry.event("cache","pending_failure");log.warn("点赞已受理，Pending缓存失败 operationId={}",operation);}
            return new Accepted(true,operation,desired,command.version());
        } finally {telemetry.time("api",started);}
    }
    public record Accepted(boolean accepted,String operationId,boolean liked,String version) { }
}
