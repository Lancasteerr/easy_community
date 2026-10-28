package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.infrastructure.config.*;
import com.febrie.backend.media.infrastructure.NotificationTimeouts;
import com.qcloud.cos.model.*;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** COS原生POST与条件复制；官方SDK负责解析HTTP 200中的复制错误。 */
@Component
@Profile("prod")
public class CosUploadStorage implements UploadStorage {
    private final MediaProperties p;
    private final MediaWorkflowProperties flow;
    public CosUploadStorage(MediaProperties p, MediaWorkflowProperties flow) { this.p = p; this.flow = flow; }
    @Override public Descriptor authorize(String key, String mime, long size, Instant created, Instant expires) {
        return new Descriptor("POST", p.uploadEndpoint().toString(), PostPolicies.cos(p, flow, key, mime, size, created, expires), expires);
    }
    @Override public void copy(UploadedObject source, String target, ProcessingDeadline deadline) {
        var client = CosClients.open(p, deadline.remainingMillis());
        try {
            var request = new CopyObjectRequest(p.bucket(), source.key(), p.bucket(), target);
            request.setMatchingETagConstraints(List.of(source.etag()));
            var metadata = new ObjectMetadata();
            metadata.setContentType(source.declaredContentType()); metadata.setCacheControl("public, max-age=3600");
            request.setNewObjectMetadata(metadata);
            if (client.copyObject(request).getETag() == null) { throw new IllegalStateException("复制结果缺少对象标识"); }
        } catch (RuntimeException failure) { throw NotificationTimeouts.translate(failure); }
        finally { client.shutdown(); }
    }
    @Override public void delete(String key) {
        var client = CosClients.open(p, 3000);
        try { client.deleteObject(p.bucket(), key); } finally { client.shutdown(); }
    }
}
