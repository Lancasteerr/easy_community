package com.febrie.backend.article.like.infrastructure.maintenance;

import com.febrie.backend.article.like.application.maintenance.LikeAuditService;
import com.febrie.backend.article.like.application.maintenance.LikeRecoveryService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.messaging.LikeDeadLetterReplay;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import java.util.Map;
import org.springframework.boot.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** 维护只运行于非Web模式，输出安全JSON结果；失败抛出并以非零状态退出。 */
@Component
public class LikeMaintenance implements ApplicationRunner {
    private final LikeProperties properties;
    private final ApplicationContext context;
    private final LikeVersionAllocator allocator;
    private final LikeRecoveryService recovery;
    private final LikeDeadLetterReplay replay;
    private final LikeAuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public LikeMaintenance(LikeProperties properties, ApplicationContext context,
            LikeVersionAllocator allocator, LikeRecoveryService recovery,
            LikeDeadLetterReplay replay, LikeAuditService audit, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.properties=properties; this.context=context; this.allocator=allocator; this.recovery=recovery;
        this.replay=replay; this.audit=audit; this.jdbc=jdbc; this.mapper=mapper;
    }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if (!properties.maintaining()) { return; }
        if (context instanceof WebApplicationContext) {
            throw new IllegalStateException("点赞维护必须使用非Web模式；恢复前必须人工停止全部业务实例");
        }
        Object result = switch (properties.maintenance()) {
            case "status" -> {
                int epoch=jdbc.queryForObject("SELECT epoch FROM like_version_epoch WHERE id=1", Integer.class);
                var current=allocator.status();
                boolean ready=allocator.ready()&&Integer.toString(epoch).equals(current.get(0));
                var details=Map.of("sqlEpoch",epoch,"redisEpoch",current.get(0),"sequence",current.get(1),"state",current.get(2),"ready",ready);
                if(!ready){System.out.println(mapper.writeValueAsString(Map.of("action","status","result",details)));throw new IllegalStateException("点赞分配器未就绪，禁止开放写入口");}
                yield details;
            }
            case "pause" -> { allocator.pause(); yield Map.of("paused", true); }
            case "recover" -> Map.of("epoch", recovery.recover(properties.expectedEpoch()));
            case "replay" -> Map.of("replayed", replay.replay(properties.replayLimit()));
            case "audit" -> {
                var differences = audit.discrepancies();
                if (!differences.isEmpty()) { System.out.println(mapper.writeValueAsString(differences)); throw new IllegalStateException("点赞对账不一致，未执行修数"); }
                yield Map.of("consistent", true);
            }
            default -> throw new IllegalArgumentException("未知维护动作");
        };
        System.out.println(mapper.writeValueAsString(Map.of("action", properties.maintenance(), "result", result)));
    }
}
