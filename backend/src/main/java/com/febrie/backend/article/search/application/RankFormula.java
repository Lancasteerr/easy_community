package com.febrie.backend.article.search.application;

import java.time.Instant;

/** 固定时间基线使冷文章不必随年龄增长周期重写；编辑不能改变发布时间。 */
public final class RankFormula {
    public static final Instant EPOCH = Instant.parse("2020-01-01T00:00:00Z");
    public static final double LAMBDA = Math.log(2) / 48;
    private RankFormula() { }
    public static double score(long views, Instant publishedAt) {
        return Math.log1p(views) + LAMBDA * ((publishedAt.toEpochMilli() - EPOCH.toEpochMilli()) / 3_600_000.0);
    }
}
