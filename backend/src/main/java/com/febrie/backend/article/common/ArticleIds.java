package com.febrie.backend.article.common;

/** 仅接受十进制正BIGINT字符串，禁止数值强制转换导致客户端精度损失。 */
public final class ArticleIds {
    private ArticleIds() { }
    public static long parse(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw ArticleErrors.invalid(); }
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw ArticleErrors.invalid(); }
    }
}
