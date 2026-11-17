package com.febrie.backend.article.like;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.*;

/** 管理API统计是异步刷新值，与250ms SQL覆盖采样分开保存。 */
final class LikePerfBroker implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String base, authorization, vhost, queuePrefix;
    LikePerfBroker(LikePerfFixture fixture) {
        mapper = fixture.mapper;
        base = System.getenv().getOrDefault("IT_RABBITMQ_MANAGEMENT_URL", "http://127.0.0.1:17973");
        vhost = URLEncoder.encode(System.getenv("IT_RABBITMQ_VHOST"), StandardCharsets.UTF_8);
        authorization = "Basic " + Base64.getEncoder().encodeToString((System.getenv("RABBITMQ_USERNAME") + ":"
                + System.getenv("RABBITMQ_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        queuePrefix = fixture.properties.name("");
    }
    private JsonNode get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/" + path))
                .timeout(Duration.ofSeconds(3)).header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) { throw new IllegalStateException("Broker管理API失败 status=" + response.statusCode()); }
        return mapper.readTree(response.body());
    }
    Snapshot sample() throws Exception {
        List<Map<String, Object>> queues = new ArrayList<>();
        long ready = 0, unacked = 0, ack = 0, dead = 0;
        for (var queue : get("queues/" + vhost)) {
            String name = queue.path("name").asString();
            if (!name.startsWith(queuePrefix)) { continue; }
            long r = queue.path("messages_ready").asLong(), u = queue.path("messages_unacknowledged").asLong();
            long a = queue.path("message_stats").path("ack").asLong();
            boolean isDead = name.endsWith("dead.q");
            if (isDead) { dead += r + u; } else { ready += r; unacked += u; ack += a; }
            queues.add(Map.of("queue", name, "ready", r, "unacked", u, "ack", a, "consumers", queue.path("consumers").asInt()));
        }
        if (queues.size() != 5) { throw new IllegalStateException("本轮Broker队列数量不是5"); }
        return new Snapshot(Instant.now().toString(), ready, unacked, ack, dead, queues);
    }
    List<Map<String, Object>> consumers() throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var consumer : get("consumers/" + vhost)) {
            String name = consumer.path("queue").path("name").asString();
            if (name.startsWith(queuePrefix)) {
                result.add(Map.of("queue", name, "active", consumer.path("active").asBoolean(),
                        "prefetch", consumer.path("prefetch_count").asInt(),
                        "connection", consumer.path("channel_details").path("connection_name").asString()));
            }
        }
        return result;
    }
    record Snapshot(String at, long ready, long unacked, long ack, long dead, List<Map<String, Object>> queues) { }
    @Override public void close() { http.close(); }
}
