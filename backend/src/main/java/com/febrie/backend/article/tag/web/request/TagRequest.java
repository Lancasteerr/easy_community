package com.febrie.backend.article.tag.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 标签录入只接受名称，业务层另按Unicode码点校验规范化结果。 */
public record TagRequest(@NotBlank @Size(max = 128) String name) { }
