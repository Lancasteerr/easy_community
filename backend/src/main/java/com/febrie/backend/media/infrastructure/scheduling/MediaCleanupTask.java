package com.febrie.backend.media.infrastructure.scheduling;

import com.febrie.backend.media.application.MediaLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅正常Web服务触发维护，管理员初始化等非Web命令不执行文件回收。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MediaCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(MediaCleanupTask.class);
    private final MediaLifecycleService lifecycle;

    public MediaCleanupTask(MediaLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @Scheduled(fixedDelayString = "${app.media.cleanup-delay:5m}", initialDelayString = "${app.media.cleanup-delay:5m}")
    public void cleanup() {
        try { lifecycle.maintain(); }
        catch (Exception failure) { log.warn("文件维护调度暂不可用：{}", failure.getClass().getSimpleName()); }
    }
}
