package com.nova.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Reviewer 的仲裁结果。
 *
 * <p>Reviewer 不重新打分，只做一件事：判断 Evaluator 的分数与证据是否矛盾，
 * 矛盾才调整，并且每次调整都要写原因。这样"复核"不会退化成
 * "再问一次模型、拿一个可能不同的分"。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewDecision(boolean adjusted, List<Adjustment> adjustments, String reason) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Adjustment(String axis, int from, int to, String why) {
    }

    public ReviewDecision {
        adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
    }

    public static ReviewDecision noop(String reason) {
        return new ReviewDecision(false, List.of(), reason);
    }
}
