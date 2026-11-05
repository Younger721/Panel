package com.nova.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Planner 产出的考察点矩阵：技能 × 难度 × 轮次。
 *
 * <p>这是多 Agent 方案里<b>唯一约束"考什么"的地方</b>。Interviewer 只能从
 * 未覆盖的点里挑题，所以"连续两轮考同一个点"在结构上不可能发生，
 * 而不是靠提示词里写一句"请勿重复"。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewPlan(String strategy, List<Point> points) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(String id, String skill, String difficulty, Integer weight, List<String> expectedSignals) {
    }

    public InterviewPlan {
        points = points == null ? List.of() : List.copyOf(points);
    }

    public Point point(String id) {
        return points.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
}
