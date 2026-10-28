package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.infrastructure.config.*;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 生产必须调用真实同步审核，只审核固定桶内临时Key，不接受任意公网URL。 */
@Component
@Profile("prod")
public class CosContentModeration implements ContentModeration {
    private final MediaProperties p;
    private final MediaWorkflowProperties flow;
    public CosContentModeration(MediaProperties p, MediaWorkflowProperties flow) { this.p = p; this.flow = flow; }
    @Override public Decision review(UploadedObject object, ProcessingDeadline deadline) {
        var client = CosClients.open(p, deadline.remainingMillis(ProcessingDeadline.COMMIT_RESERVE_MS));
        try {
            var request = new ImageAuditingRequest();
            request.setBucketName(p.bucket()); request.setObjectKey(object.key()); request.setBizType(flow.moderationBizType());
            request.setAsync("0");
            if (object.size() > 5L * 1024 * 1024) { request.setLargeImageDetect("1"); }
            var response = client.imageAuditing(request);
            if (!"Success".equals(response.getState())) { throw new IllegalStateException("审核未同步完成"); }
            return switch (response.getResult() == null ? "" : response.getResult()) {
                case "0" -> Decision.PASS;
                case "1" -> Decision.REJECT;
                case "2" -> Decision.REVIEW_REQUIRED;
                default -> throw new IllegalStateException("未知审核结论");
            };
        } finally { client.shutdown(); }
    }
}
