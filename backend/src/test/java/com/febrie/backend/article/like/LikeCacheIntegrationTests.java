package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.redis.LikeCache;

import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.domain.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Lua CAS与固定寿命测试；只清随机测试命名空间。 */
@EnabledIfEnvironmentVariable(named="IT_REDIS",matches="true")
class LikeCacheIntegrationTests {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    LikeCache cache;
    @BeforeEach void connect() {
        var server=new RedisStandaloneConfiguration(System.getenv().getOrDefault("IT_REDIS_HOST","127.0.0.1"),Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT","6379")));
        server.setPassword(System.getenv().getOrDefault("IT_REDIS_PASSWORD",""));
        factory=new LettuceConnectionFactory(server);factory.afterPropertiesSet();factory.start(); redis=new StringRedisTemplate(factory);
        var p=new LikeProperties(true,false,false,"ec:test:"+UUID.randomUUID()+":like",Duration.ofSeconds(2),128,2,20,67108864,60,4,Duration.ofMillis(300),"none",-1,0);
        cache=new LikeCache(redis,p);
    }
    @AfterEach void clean() { if(cache!=null) { redis.delete(List.of(cache.stateKey(1),cache.countKey(2),cache.countKey(3))); } if(factory!=null) {factory.destroy();} }

    @Test void committedWinsPendingAndFullLongVersionsRemainExact() {
        long version=LikeVersion.pack(1024,1);
        var state=new LikeState(1,2,true,version);
        cache.putStates(1,List.of(state),"C"); cache.pending(new LikeCommand(1,UUID.randomUUID().toString(),1L,2L,true,Long.toString(version),java.time.Instant.now()));
        assertTrue(redis.opsForHash().get(cache.stateKey(1),"2").toString().endsWith("|C"));
        assertEquals(state,cache.putStates(1,List.of(new LikeState(1,2,false,version-1)),"C").get(2L));
        assertThrows(RuntimeException.class,()->cache.putStates(1,List.of(new LikeState(1,2,false,version)),"P"));
        assertEquals(state,cache.states(1,List.of(2L)).get(2L));
    }

    @Test void updatesOtherFieldsAndDuplicateMessagesNeverRefreshHashExpiry() {
        cache.putStates(1,List.of(new LikeState(1,2,true,10)),"P");
        assertTrue(redis.getExpire(cache.stateKey(1))<=600);
        redis.expire(cache.stateKey(1),Duration.ofSeconds(90));
        cache.putStates(1,List.of(new LikeState(1,3,false,12)),"C");
        cache.putStates(1,List.of(new LikeState(1,2,true,10)),"C");
        assertTrue(redis.getExpire(cache.stateKey(1))<=90);
        cache.putCounts(Map.of(2L,20L)); redis.expire(cache.countKey(2),Duration.ofSeconds(10));
        cache.putCounts(Map.of(2L,21L));
        assertEquals(20L,cache.counts(List.of(2L)).get(2L));
        assertTrue(redis.getExpire(cache.countKey(2))<=10);
    }

    @Test void staleDoesNotRecreateEmptyCacheAndLateOldCallbacksAreBounded() {
        var stale=new LikeSqlService.Result(LikeSqlService.Status.STALE,new LikeState(1,2,false,12));
        cache.committed(stale); assertFalse(Boolean.TRUE.equals(redis.hasKey(cache.stateKey(1))));
        // 丢失水位后旧回调可能短暂回退：这是展示边界，必须有固定TTL而不是永久保存。
        cache.putStates(1,List.of(new LikeState(1,2,true,11)),"P");
        assertTrue(redis.getExpire(cache.stateKey(1))>0);
        cache.committed(new LikeSqlService.Result(LikeSqlService.Status.APPLIED,new LikeState(1,2,false,12)));
        assertFalse(cache.states(1,List.of(2L)).get(2L).liked());
    }
}
