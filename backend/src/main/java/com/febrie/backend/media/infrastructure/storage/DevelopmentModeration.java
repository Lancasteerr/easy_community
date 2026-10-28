package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.application.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 仅开发和测试装配，不等待、不模拟真实审核，也不提供真实格式结论。 */
@Component
@Profile({"dev", "test"})
public class DevelopmentModeration implements ContentModeration {
    @Override public Decision review(UploadedObject object, ProcessingDeadline deadline) { deadline.check(); return Decision.PASS; }
}
