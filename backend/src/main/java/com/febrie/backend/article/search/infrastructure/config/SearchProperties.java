package com.febrie.backend.article.search.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 检索配置只决定连接、容量与维护动作；普通启动不检查远端连通性。 */
@ConfigurationProperties("app.search")
public record SearchProperties(
        @DefaultValue("http://127.0.0.1:9200") URI uri,
        @DefaultValue("") String username, @DefaultValue("") String password,
        @DefaultValue("article_v1") String indexName,
        @DefaultValue("article_read") String readAlias, @DefaultValue("article_write") String writeAlias,
        @DefaultValue("0") int replicas, @DefaultValue("false") boolean queryEnabled,
        @DefaultValue("") String cursorSecret, @DefaultValue("true") boolean rankSchedulingEnabled,
        @DefaultValue("none") String maintenance, @DefaultValue("") String targetIndex,
        @DefaultValue("100") int replayLimit, @DefaultValue("ec:dev:search") String namespace,
        @DefaultValue("2s") Duration connectTimeout, @DefaultValue("3s") Duration responseTimeout) {
    public SearchProperties {
        if (uri == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || replicas < 0 || replayLimit < 1 || replayLimit > 10000
                || !Set.of("none", "init", "import", "calibrate", "audit", "repair", "replay", "switch").contains(maintenance)
                || !namespace.matches("ec:(dev|test|prod)[A-Za-z0-9:_-]*:search")
                || indexName.equals(readAlias) || indexName.equals(writeAlias) || readAlias.equals(writeAlias)
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("文章检索配置无效");
        }
        if (queryEnabled && cursorSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("开启检索需要至少32字节的共享游标签名密钥");
        }
    }
    public boolean maintaining() { return !maintenance.equals("none"); }
    public String target() { return targetIndex.isBlank() ? indexName : targetIndex; }
    public String name(String suffix) { return namespace.replace(':', '.') + ".article.index." + suffix; }
}
