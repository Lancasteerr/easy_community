package com.febrie.backend.article.search.application;

import co.elastic.clients.elasticsearch._types.FieldValue;
import java.util.List;

/** 数值以带类型的文本保存在不透明游标内，避免BIGINT经过double丢失精度。 */
public record SearchCursor(int version, String fingerprint, String pit, List<Value> sort, long expiresAt) {
    public record Value(String type, String value) {
        public static Value from(FieldValue field) {
            if (field.isLong()) { return new Value("long", Long.toString(field.longValue())); }
            if (field.isDouble()) { return new Value("double", Double.toString(field.doubleValue())); }
            if (field.isString()) { return new Value("string", field.stringValue()); }
            throw new IllegalStateException("ES返回了不支持的排序类型");
        }
        public FieldValue field() {
            return switch (type) {
                case "long" -> FieldValue.of(Long.parseLong(value));
                case "double" -> FieldValue.of(Double.parseDouble(value));
                case "string" -> FieldValue.of(value);
                default -> throw SearchErrors.invalid();
            };
        }
    }
}
