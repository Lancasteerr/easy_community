package com.febrie.backend.article.search.infrastructure.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** ES私有Jackson 2和HTTP连接池，不注册或替换Web的ObjectMapper。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {
    @Bean(destroyMethod = "close")
    public ElasticsearchClient articleElasticsearch(SearchProperties p) {
        var builder = Rest5Client.builder(HttpHost.create(p.uri()));
        builder.setConnectionConfigCallback(c -> c.setConnectTimeout(Timeout.ofMilliseconds(p.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(p.responseTimeout().toMillis())));
        builder.setRequestConfigCallback(c -> c.setResponseTimeout(Timeout.ofMilliseconds(p.responseTimeout().toMillis()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(p.connectTimeout().toMillis())));
        if (!p.username().isBlank()) {
            String credentials = Base64.getEncoder().encodeToString((p.username() + ":" + p.password()).getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + credentials)});
        }
        return new ElasticsearchClient(new Rest5ClientTransport(builder.build(), new JacksonJsonpMapper()));
    }
}
