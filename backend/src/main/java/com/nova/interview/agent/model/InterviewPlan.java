package com.nova.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 考察点矩阵：面试要问的点，及其来源。
 *
 * <p>每个点都绑定简历分析里的某个小点（{@code quote} + {@code comment}），
 * 所以"面试问的"和"批注里标的"是同一批点——用户看到的问题不会凭空出现。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewPlan(String strategy, List<Point> points) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(String id, String skill, String difficulty, Integer weight,
                        List<String> expectedSignals, String quote, String comment, String level) {

        public Point {
            expectedSignals = expectedSignals == null ? List.of() : List.copyOf(expectedSignals);
        }
    }

    public InterviewPlan {
        points = points == null ? List.of() : List.copyOf(points);
    }

    public Point point(String id) {
        return points.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
}
