package com.nova.interview.agent;

import com.nova.interview.agent.model.InterviewPlan;
import com.nova.interview.application.ResumeAnalysisService.AnalysisView;
import com.nova.interview.application.ResumeAnalysisService.ModuleView;
import com.nova.interview.application.ResumeAnalysisService.PointView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner：从简历批注里挑出本场面试要问的点。
 *
 * <p>这里<b>不调模型</b>：选题是确定性的，规则是"先问写弱的、同一模块最多两点、
 * 模块间轮转"，因此同一份简历两次面试的考点一致，也保证了覆盖率。
 * 真正需要模型的是出题和追问（{@link InterviewerAgent}）。</p>
 */
@Component
public class PlannerAgent {

    /** 同一模块最多出几道主题，避免一场面试全在问同一个项目。 */
    private static final int MAX_PER_MODULE = 2;

    public InterviewPlan plan(AnalysisView analysis, int questionCount) {
        List<Candidate> all = new ArrayList<>();
        int seq = 0;
        for (ModuleView module : analysis.modules()) {
            for (PointView point : module.points()) {
                all.add(new Candidate(++seq, module.type(), module.title(), point));
            }
        }
        if (all.isEmpty()) {
            throw new IllegalStateException("resume has no annotation point to interview on");
        }

        // 弱点优先：写得不充分的地方才值得追问
        List<Candidate> ordered = all.stream()
                .sorted(Comparator.comparingInt((Candidate c) -> priority(c.point().level()))
                        .thenComparingInt(Candidate::seq))
                .toList();

        List<Candidate> chosen = new ArrayList<>();
        Map<String, Integer> perModule = new HashMap<>();
        for (Candidate candidate : ordered) {
            if (chosen.size() >= questionCount) {
                break;
            }
            int used = perModule.getOrDefault(candidate.moduleType(), 0);
            if (used >= MAX_PER_MODULE) {
                continue;
            }
            chosen.add(candidate);
            perModule.put(candidate.moduleType(), used + 1);
        }
        // 模块太集中导致选不满时，放开模块配额
        if (chosen.size() < questionCount) {
            for (Candidate candidate : ordered) {
                if (chosen.size() >= questionCount) {
                    break;
                }
                if (!chosen.contains(candidate)) {
                    chosen.add(candidate);
                }
            }
        }
        chosen.sort(Comparator.comparingInt(Candidate::seq));

        List<InterviewPlan.Point> points = new ArrayList<>();
        int index = 0;
        for (Candidate candidate : chosen) {
            PointView point = candidate.point();
            points.add(new InterviewPlan.Point("P" + (++index), point.title(),
                    difficultyOf(point.level()), 1, List.of(truncate(point.comment(), 120)),
                    point.quote(), point.comment(), point.level()));
        }
        String strategy = "优先考察简历中表述薄弱的点：" + points.stream()
                .map(InterviewPlan.Point::skill).limit(3).reduce((a, b) -> a + "、" + b).orElse("");
        return new InterviewPlan(strategy, points);
    }

    private static int priority(String level) {
        return switch (level == null ? "" : level) {
            case "WEAK" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private static String difficultyOf(String level) {
        return switch (level == null ? "" : level) {
            case "WEAK" -> "BASIC";
            case "STRONG" -> "EXPERT";
            default -> "ADVANCED";
        };
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private record Candidate(int seq, String moduleType, String moduleTitle, PointView point) {
    }
}
