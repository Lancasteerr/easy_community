package com.febrie.backend.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 环境选择与代理信任必须显式配置，不能悄悄回退到开发配置。 */
class RuntimeConfigurationTests {
    private AuthProperties properties(String environment, boolean secure) {
        return new AuthProperties(environment, "ec:" + environment, secure, Duration.ofHours(2),
                60, 30, 120, 5, 30, Duration.ofMinutes(10));
    }

    @Test
    void rejectsMissingOrMixedProfiles() {
        var env = new MockEnvironment();
        assertThrows(IllegalStateException.class, () -> new RuntimeConfiguration(env, properties("dev", false)));
        env.setActiveProfiles("dev", "prod");
        assertThrows(IllegalStateException.class, () -> new RuntimeConfiguration(env, properties("dev", false)));
    }

    @Test
    void productionRequiresExplicitProxyAndSecureCookie() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new RuntimeConfiguration(env, properties("prod", true)));
        env.setProperty("server.tomcat.remoteip.internal-proxies", ".*");
        assertThrows(IllegalStateException.class, () -> new RuntimeConfiguration(env, properties("prod", true)));
        env.setProperty("server.tomcat.remoteip.internal-proxies", "172\\.30\\.0\\.10");
        assertDoesNotThrow(() -> new RuntimeConfiguration(env, properties("prod", true)));
        assertThrows(IllegalStateException.class, () -> new RuntimeConfiguration(env, properties("prod", false)));
    }
}
