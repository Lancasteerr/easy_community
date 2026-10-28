package com.febrie.backend.media.web;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.application.dto.UploadResult;
import com.febrie.backend.media.domain.MediaLocation;
import com.febrie.backend.media.infrastructure.config.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.security.MessageDigest;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import static com.febrie.backend.media.application.dto.UploadResult.*;

/** HTTP适配层仅在认证后把通知转换为共用上传上下文。 */
@Component
public class StorageNotificationHandler {
    private static final Logger log = LoggerFactory.getLogger(StorageNotificationHandler.class);
    private final MediaProperties p;
    private final MediaWorkflowProperties flow;
    private final FinishUpload finish;
    private final boolean production;
    private final ObjectMapper json = new ObjectMapper();
    public StorageNotificationHandler(MediaProperties p, MediaWorkflowProperties flow, FinishUpload finish, Environment env) {
        this.p = p; this.flow = flow; this.finish = finish; this.production = env.matchesProfiles("prod");
    }
    public boolean accepts(String path) {
        return (production ? "/api/storage/cos/upload-callback" : "/api/storage/minio/upload-webhook").equals(path);
    }
    public int maxBytes() { return production ? flow.callbackMaxBytes() : flow.webhookMaxBytes(); }
    public boolean acceptsAuthenticationHeader(HttpServletRequest request) {
        if (production) { return true; }
        String header = request.getHeader("Authorization");
        return header != null && !flow.webhookToken().isBlank() && MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), ("Bearer " + flow.webhookToken()).getBytes(StandardCharsets.UTF_8));
    }
    public Response handle(HttpServletRequest request, byte[] body, ProcessingDeadline deadline) {
        if (!production) { return webhook(request, body, deadline); }
        String signature = request.getHeader("x-cos-callback-encrypted-auth");
        if (!CosCallbackAuth.verify(request.getRequestURI(), request.getQueryString(), body, signature, flow.callbackKey())) {
            return new Response(403, null, failed(Stage.AUTH, Code.INVALID_CALLBACK));
        }
        try {
            deadline.check();
            JsonNode root = json.readTree(body);
            var object = new UploadedObject(text(root, "bucket"), text(root, "key"), size(root.get("size")),
                    text(root, "mimeType"), text(root, "etag"));
            String id = new MediaLocation(p.keyPrefix()).idFromTempKey(object.key());
            return new Response(200, id, finish.finishUpload(object, deadline));
        } catch (ProcessingDeadline.TimedOut error) { return new Response(200, null, failed(deadline.stage(), Code.PROCESS_TIMEOUT)); }
        catch (RuntimeException error) { return new Response(400, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); }
    }
    private Response webhook(HttpServletRequest request, byte[] body, ProcessingDeadline deadline) {
        if (!acceptsAuthenticationHeader(request)) { return new Response(403, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); }
        JsonNode records;
        try {
            records = json.readTree(body).get("Records");
            if (records == null || !records.isArray() || records.isEmpty() || records.size() > flow.webhookMaxRecords()) {
                throw new IllegalArgumentException("通知批次无效");
            }
        } catch (RuntimeException error) { return new Response(400, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); }
        int index = 0;
        for (JsonNode record : records) {
            UploadResult result;
            try {
                deadline.stage(Stage.AUTH);
                deadline.check();
                if (!"s3:ObjectCreated:Post".equals(text(record, "eventName"))) { throw new IllegalArgumentException("非POST事件"); }
                JsonNode s3 = record.get("s3");
                JsonNode object = s3.get("object");
                String key = URLDecoder.decode(text(object, "key"), StandardCharsets.UTF_8);
                String mime = null;
                JsonNode metadata = object.get("userMetadata");
                if (metadata != null && metadata.isObject()) {
                    for (var property : metadata.properties()) {
                        if (property.getKey().equalsIgnoreCase("content-type") && property.getValue().isString()) { mime = property.getValue().asString(); }
                    }
                }
                result = finish.finishUpload(new UploadedObject(text(s3.get("bucket"), "name"), key,
                        size(object.get("size")), mime, text(object, "eTag")), deadline);
            } catch (ProcessingDeadline.TimedOut error) { result = failed(deadline.stage(), Code.PROCESS_TIMEOUT); }
            catch (RuntimeException error) { result = failed(Stage.AUTH, Code.INVALID_CALLBACK); }
            log.info("MinIO批次项目：index={} stage={} code={}", index++, result.stage(), result.code());
        }
        // 业务失败也确认接收，不将MinIO重投当成发布恢复机制。
        return new Response(200, null, completed());
    }
    static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) { throw new IllegalArgumentException("通知字符串缺失"); }
        return value.asString();
    }
    static long size(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("通知大小无效");
        }
        return value.asLong();
    }
    public record Response(int status, String mediaId, UploadResult result) { }
}
