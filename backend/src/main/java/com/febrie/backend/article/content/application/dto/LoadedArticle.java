package com.febrie.backend.article.content.application.dto;

/** SQL已确认的文章状态；依赖异常必须抛出，不能伪装成MISSING。 */
public record LoadedArticle<T>(State state, T data) {
    public enum State { PUBLIC, MISSING, HIDDEN }
    public static <T> LoadedArticle<T> missing() { return new LoadedArticle<>(State.MISSING, null); }
    public static <T> LoadedArticle<T> hidden() { return new LoadedArticle<>(State.HIDDEN, null); }
    public static <T> LoadedArticle<T> visible(T data) { return new LoadedArticle<>(State.PUBLIC, data); }
}
