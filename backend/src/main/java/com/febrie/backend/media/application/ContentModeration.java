package com.febrie.backend.media.application;

/** 审核只返回结论，不持有数据库、不复制对象，迟到结果不能自行发布。 */
public interface ContentModeration {
    Decision review(UploadedObject object, ProcessingDeadline deadline);
    enum Decision { PASS, REJECT, REVIEW_REQUIRED }
}
