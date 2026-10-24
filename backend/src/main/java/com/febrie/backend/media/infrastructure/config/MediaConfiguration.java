package com.febrie.backend.media.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 启动仅检查本地配置，不创建桶、不修改生命周期或权限。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfiguration {
    public MediaConfiguration(MediaProperties properties, Environment environment) {
        if (environment.matchesProfiles("prod") && (!"https".equals(properties.uploadEndpoint().getScheme())
                || !"https".equals(properties.publicBaseUrl().getScheme()))) {
            throw new IllegalStateException("生产环境上传与公开文件地址必须使用HTTPS");
        }
        for (String profile : new String[]{"dev", "prod"}) {
            if (environment.matchesProfiles(profile) && !properties.keyPrefix().endsWith("/" + profile)) {
                throw new IllegalStateException("文件项目前缀必须以当前环境名结尾，避免跨环境回收");
            }
        }
    }
}
