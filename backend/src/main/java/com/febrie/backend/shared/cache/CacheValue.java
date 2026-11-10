package com.febrie.backend.shared.cache;

/** 单投影的三态，不把无Key和明确不存在混为一谈。 */
public record CacheValue<T>(State state, T data) {
    public enum State { HIT, NEGATIVE, MISS }
    public static <T> CacheValue<T> miss() { return new CacheValue<>(State.MISS, null); }
    public static <T> CacheValue<T> negative() { return new CacheValue<>(State.NEGATIVE, null); }
    public static <T> CacheValue<T> hit(T data) { return new CacheValue<>(State.HIT, data); }
}
