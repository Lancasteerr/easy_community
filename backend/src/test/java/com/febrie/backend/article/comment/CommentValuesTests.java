package com.febrie.backend.article.comment;

import com.febrie.backend.shared.cache.CacheProtocol;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回填时的负缓存租期与再次读取均由缓存客户端替身验证。 */
class CommentValuesTests {
    @SuppressWarnings("unchecked")
    @Test
    void missingActiveRowIsNegativelyCachedUnderOperationLock() {
        var redis = mock(StringRedisTemplate.class);
        var commands = (ValueOperations<String, String>) mock(ValueOperations.class);
        var store = mock(CommentStore.class);
        String key = "test:comment:v1:visible-object:{7}";
        String lock = key + ":lock";
        when(redis.opsForValue()).thenReturn(commands);
        var missing = Collections.<String>singletonList(null);
        when(commands.multiGet(List.of(key))).thenReturn(missing, missing, List.of("-"));
        when(commands.setIfAbsent(eq(lock), anyString(), eq(CacheProtocol.LOCK_TTL))).thenReturn(true);
        when(store.findAll(anyCollection())).thenReturn(Map.of());
        when(redis.execute(any(RedisScript.class), eq(List.of(lock, key)),
                anyString(), eq("write"), eq("2000"), eq("-"))).thenReturn(1L);

        var values = new CommentValues(store, redis, new JsonMapper(), "test");
        assertTrue(values.objects(List.of(7L)).isEmpty());
        assertTrue(values.objects(List.of(7L)).isEmpty());
        verify(store).findAll(anyCollection());
        verify(commands, times(3)).multiGet(List.of(key));
    }
}
