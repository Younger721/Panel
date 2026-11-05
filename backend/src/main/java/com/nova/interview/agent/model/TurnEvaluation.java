package com.nova.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Evaluator 的逐题评分。
 *
 * <p>关键约束：每个扣分/给分都必须带 {@code evidence.quote}——回答原文里的片段。
 * 没有证据的评分在 {@code InterviewService} 里会被判无效并触发 Reviewer，
 * 这是"分数可追溯"的落地方式，也是这套评估比"让模型给个分"更可信的地方。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnEvaluation(Scores scores, List<Evidence> evidence, List<String> deductions,
                             String feedback, Boolean answered) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scores(int accuracy, int depth, int completeness, int clarity) {

        /** 与面试官评分惯例对齐：准确性 40%、深度 25%、完整性 20%、表达 15%。 */
        public int weightedTotal() {
            return (int) Math.round(accuracy * 0.40 + depth * 0.25 + completeness * 0.20 + clarity * 0.15);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(String quote, String note) {
    }

    public TurnEvaluation {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        deductions = deductions == null ? List.of() : List.copyOf(deductions);
    }

    /** 没有一条引用原文的证据，视为无效评分。 */
    public boolean hasEvidence() {
        return evidence.stream().anyMatch(e -> e.quote() != null && !e.quote().isBlank());
    }

    public boolean isAnswered() {
        return Boolean.TRUE.equals(answered);
    }
}
