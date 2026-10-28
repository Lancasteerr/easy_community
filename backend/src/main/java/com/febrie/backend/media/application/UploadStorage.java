package com.febrie.backend.media.application;

import java.time.Instant;
import java.util.Map;

/** 隔离POST签名、一次公开复制和幂等删除；业务流程不执行HEAD。 */
public interface UploadStorage {
    Descriptor authorize(String key, String mime, long size, Instant created, Instant expires);
    void copy(UploadedObject source, String target, ProcessingDeadline deadline);
    void delete(String key);
    record Descriptor(String method, String url, Map<String,String> fields, Instant expiresAt) { }
}
