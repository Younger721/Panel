package com.panel.knowledge.application;

public record KbSearchDebug(
        String mode,
        long latencyMs,
        int keywordHits,
        int vectorHits,
        int fused,
        boolean reranked,
        boolean degraded,
        double threshold,
        int topK) {
}
