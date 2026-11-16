package com.panel.audit.application;

import java.math.BigDecimal;

/**
 * 单个 feature 的用量与成本汇总。
 *
 * <p>为什么需要它：{@link AiUsageSummary} 把整段时间的调用混在一起，只能回答
 * "花了多少钱"，回答不了"钱花在哪个功能上"。而项目里有 19 个模型调用点
 * （批注、出题、评分、追问、报告、RAG 问答、OCR……），不按 feature 拆开，
 * 优化成本时根本不知道先动哪个——很可能砍了半天，砍掉的是最便宜的那块。</p>
 */
public record AiFeatureUsage(
        String feature,
        long calls,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        double averageLatencyMs,
        double successRate) {
}
