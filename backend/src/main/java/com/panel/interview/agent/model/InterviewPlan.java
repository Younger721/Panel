package com.panel.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 考察点矩阵：面试要问的点，及其来源。
 *
 * <p>每个点都绑定简历分析里的某个小点（{@code quote} + {@code comment}），
 * 所以"面试问的"和"批注里标的"是同一批点——用户看到的问题不会凭空出现。</p>
 *
 * <p>{@code jd} / {@code fit} 是岗位匹配的产物，整份存在 {@code plan_json} 里：
 * 老会话没有这两个字段，反序列化后为 null，行为和加 JD 之前一致，所以不需要改表。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewPlan(String strategy, List<Point> points, String jd, JobFit fit) {

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

    /** 纯简历面试（没有 JD）。 */
    public InterviewPlan(String strategy, List<Point> points) {
        this(strategy, points, null, null);
    }

    public InterviewPlan withJobFit(String jd, JobFit fit) {
        return new InterviewPlan(strategy, points, jd, fit);
    }

    public Point point(String id) {
        return points.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
}
