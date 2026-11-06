package com.febrie.backend.article.tag.application.dto;

/** 目录与文章展示共用的不可变标签，不携带排序或管理状态。 */
public record TagView(String tagId, String name) { }
