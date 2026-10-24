package com.febrie.backend.media.infrastructure.config;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 只绑定文件存储配置；真实凭证来自环境文件，toString不得输出密钥。 */
@ConfigurationProperties("app.media")
public record MediaProperties(String provider, URI endpoint, URI uploadEndpoint, String region,
                              String bucket, String accessKey, String secretKey, String keyPrefix, URI publicBaseUrl) {
    public MediaProperties {
        if (!Set.of("minio", "cos").contains(provider == null ? "" : provider)
                || blank(region) || blank(bucket) || blank(accessKey) || blank(secretKey)
                || keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,127}")
                || keyPrefix.endsWith("/") || keyPrefix.contains("//")) {
            throw new IllegalArgumentException("必须配置有效的文件存储提供商、地域、桶、凭证与独立项目前缀");
        }
        validateUrl(endpoint, false);
        validateUrl(uploadEndpoint, false);
        validateUrl(publicBaseUrl, true);
    }

    /** 生成公开地址时保留记录中的完整对象Key；不在数据库保存域名。 */
    public String publicUrl(String key) {
        return publicBaseUrl.toString().replaceAll("/+$", "") + "/" + key;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void validateUrl(URI uri, boolean allowPath) {
        if (uri == null || uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (!allowPath && uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("文件存储地址必须是无凭证、查询参数和片段的HTTP地址，存储端点不能包含路径");
        }
    }

    @Override
    public String toString() { return "MediaProperties[provider=" + provider + ", credentials=REDACTED]"; }
}
