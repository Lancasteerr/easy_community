package com.febrie.backend.article.like;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 破坏性演练的双重归属检查：固定名称、测试标签和Compose项目必须同时匹配。 */
final class LikeTestContainers {
    private LikeTestContainers() { }
    static void restart(String container) throws Exception {
        assertTrue(Set.of("easy-community-like-it-redis-1", "easy-community-like-it-rabbitmq-1").contains(container));
        var inspect = new ProcessBuilder("docker", "inspect", "--format", "{{json .Config.Labels}}", container).start();
        assertTrue(inspect.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, inspect.exitValue());
        var labels = new tools.jackson.databind.ObjectMapper().readTree(new String(inspect.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("true", labels.path("ec.like.test").asString());
        assertEquals("easy-community-like-it", labels.path("com.docker.compose.project").asString());
        var restart = new ProcessBuilder("docker", "restart", container).redirectErrorStream(true)
                .redirectOutput(Path.of("target", "like-container-restart.log").toFile()).start();
        assertTrue(restart.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, restart.exitValue());
    }
}
