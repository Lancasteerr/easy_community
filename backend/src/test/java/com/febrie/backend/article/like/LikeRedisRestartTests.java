package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import com.febrie.backend.article.like.domain.LikeVersion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.awaitility.Awaitility.await;

/** 仅显式启用时重启带专用标签的测试Redis，绝不操作开发实例。 */
@EnabledIfEnvironmentVariable(named="IT_LIKE_FAULTS",matches="true")
class LikeRedisRestartTests {
    @Test void restoredReadyKeysContinueAndRollbackRecoveryUsesHigherEpoch()throws Exception{
        String container="easy-community-like-it-redis-1";
        assertEquals("17779",System.getenv("IT_REDIS_PORT"));
        var inspect=new ProcessBuilder("docker","inspect","--format","{{json .Config.Labels}}",container).start();
        assertTrue(inspect.waitFor(10,TimeUnit.SECONDS));
        assertEquals(0,inspect.exitValue());
        String labels=new String(inspect.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals("true",new tools.jackson.databind.ObjectMapper().readTree(labels).path("ec.like.test").asString());
        var config=new RedisStandaloneConfiguration("127.0.0.1",17779);config.setPassword(System.getenv("IT_REDIS_PASSWORD"));
        var factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();factory.start();
        var redis=new StringRedisTemplate(factory);
        var p=new LikeProperties("ec:test:"+UUID.randomUUID()+":like",Duration.ofSeconds(2),128,2,20,67108864,60,4,Duration.ofMillis(300),"none",-1,0);
        var allocator=new LikeVersionAllocator(redis,p,mock(LikeTelemetry.class));
        try{
            String before=allocator.recoveryRuntime();allocator.initialize(1,before);allocator.activate(1,before);
            long old=allocator.next();
            // docker正常停止Redis会持久化；重启后完整数据应在原纪元继续递增。
            LikeTestContainers.restart(container);
            await().ignoreExceptions().atMost(Duration.ofSeconds(20)).until(()->!allocator.status().get(4).equals(before));
            assertEquals("READY",allocator.status().get(2));assertEquals(3,redis.countExistingKeys(allocator.keys()));
            assertTrue(allocator.ready());assertEquals("1",allocator.status().get(1));
            assertEquals(before,allocator.status().get(3));
            assertEquals(LikeVersion.pack(1,2),allocator.next());
            // 再模拟异常宕机可能造成的回退，恢复业务前人工切换新纪元，不在旧纪元签发。
            redis.opsForValue().set(allocator.keys().get(1),"0");
            String current=allocator.recoveryRuntime();allocator.pause();allocator.initialize(2,current);allocator.activate(2,current);
            assertEquals(LikeVersion.pack(2,1),allocator.next());assertTrue(LikeVersion.pack(2,1)>old);
        }finally{redis.delete(allocator.keys());factory.destroy();}
    }
}
