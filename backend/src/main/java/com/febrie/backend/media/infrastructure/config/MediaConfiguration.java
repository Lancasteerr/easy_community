package com.febrie.backend.media.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 启动仅检查本地配置，不创建桶、不修改生命周期或权限。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MediaProperties.class, MediaWorkflowProperties.class})
public class MediaConfiguration {
    public MediaConfiguration(MediaProperties properties, MediaWorkflowProperties flow, Environment environment) {
        if (environment.matchesProfiles("prod")) {
            if (!"cos".equals(properties.provider()) || flow.callbackKey().isBlank() || flow.moderationBizType().isBlank()
                    || !flow.callbackUrl().startsWith("https://") || !flow.callbackUrl().endsWith("/api/storage/cos/upload-callback")) {
                throw new IllegalStateException("生产必须配置COS、HTTPS回调、约定验签密钥与真实审核策略");
            }
        } else if (environment.matchesProfiles("dev") && (!"minio".equals(properties.provider())
                || flow.webhookToken().isBlank() || flow.webhookToken().chars().anyMatch(Character::isWhitespace))) {
            throw new IllegalStateException("开发必须配置MinIO与单段通知token");
        }
        if (environment.matchesProfiles("prod") && (!"https".equals(properties.endpoint().getScheme())
                || !"https".equals(properties.uploadEndpoint().getScheme())
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
