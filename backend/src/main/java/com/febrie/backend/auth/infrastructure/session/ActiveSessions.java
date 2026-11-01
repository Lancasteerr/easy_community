package com.febrie.backend.auth.infrastructure.session;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Set是会话有效性的必要条件，普通请求只能查询或移除成员，不能重新登记。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ActiveSessions {
    private static final Logger log = LoggerFactory.getLogger(ActiveSessions.class);
    // KEYS[1] 为集合，ARGV 依次为 Session ID、毫秒 TTL；重复登记也刷新 TTL。
    private static final RedisScript<Long> REGISTER = RedisScript.of(
            new ClassPathResource("redis/register-session.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final long sessionTtlMillis;
    private final SessionRepository<? extends Session> repository;
    private final Clock clock;

    public ActiveSessions(StringRedisTemplate redis, AuthProperties properties,
                          SessionRepository<? extends Session> repository, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        // 配置不可变，构造时检查精度及溢出，避免 SADD 后才发现 TTL 无效。
        this.sessionTtlMillis = properties.sessionTtl().toMillis();
        Assert.isTrue(sessionTtlMillis > 0, "会话集合过期时间必须至少为一毫秒");
        this.repository = repository;
        this.clock = clock;
    }

    public String key(SessionPrincipal principal) {
        return properties.redisPrefix() + ":auth:user:" + principal.userId() + ":v:" + principal.authVersion() + ":sessions";
    }

    public void register(SessionPrincipal principal, String sessionId) {
        Long result = redis.execute(REGISTER, List.of(key(principal)), sessionId, Long.toString(sessionTtlMillis));
        if (!Long.valueOf(1).equals(result)) {
            throw new RedisSystemException("会话集合登记未成功写入", null);
        }
    }

    public boolean contains(SessionPrincipal principal, String sessionId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(key(principal), sessionId));
    }

    public void remove(SessionPrincipal principal, String sessionId) {
        redis.opsForSet().remove(key(principal), sessionId);
    }

    /** 分批清理集合中的过期成员；清理失败不改变SQL版本校验的正确性。 */
    public void cleanup() {
        try (var keys = redis.scan(ScanOptions.scanOptions()
                .match(properties.redisPrefix() + ":auth:user:*:v:*:sessions").count(100).build())) {
            while (keys.hasNext()) {
                String key = keys.next();
                try (var members = redis.opsForSet().scan(key, ScanOptions.scanOptions().count(100).build())) {
                    while (members.hasNext()) {
                        String id = members.next();
                        Session session = repository.findById(id);
                        Long expiresAt = session == null ? null : session.getAttribute("auth.expiresAt");
                        if (expiresAt == null || expiresAt <= clock.millis()) {
                            redis.opsForSet().remove(key, id);
                        }
                    }
                }
            }
        } catch (RuntimeException error) {
            // 不记录Session ID或Redis连接凭证，下一周期继续回收。
            log.warn("活跃会话集合清理暂未完成：{}", error.getClass().getSimpleName());
        }
    }
}
