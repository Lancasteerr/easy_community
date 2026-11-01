package com.febrie.backend.article.web.response;

/** 新增和修改共用响应，不包含任务状态或编辑版本。 */
public record ArticleSaveResponse(String articleId) { }
