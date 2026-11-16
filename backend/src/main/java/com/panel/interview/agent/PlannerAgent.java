package com.panel.interview.agent;

import com.panel.interview.agent.model.InterviewPlan;
import com.panel.interview.agent.model.JobFit;
import com.panel.interview.application.ResumeAnalysisService.AnalysisView;
import com.panel.interview.application.ResumeAnalysisService.ModuleView;
import com.panel.interview.application.ResumeAnalysisService.PointView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Planner：从简历批注里挑出本场面试要问的点。
 *
 * <p>这里<b>不调模型</b>：选题是确定性的，规则是"先问写弱的、同一模块最多两点、
 * 模块间轮转"，因此同一份简历两次面试的考点一致，也保证了覆盖率。
 * 真正需要模型的是出题和追问（{@link InterviewerAgent}）。</p>
 *
 * <p>只看实习、项目、技术栈三类模块。教育背景个人介绍这类内容问不出东西，
 * 真面试也不会拿它当主问题；全都缺的时候才退回全量，免得面试开不起来。</p>
 */
@Component
public class PlannerAgent {

    /** 同一模块最多出几道主题，避免一场面试全在问同一个项目。 */
    private static final int MAX_PER_MODULE = 2;

    /** 值得面试的模块：实习经历、项目经历、技术栈。 */
    private static final Set<String> FOCUS_MODULES = Set.of("INTERNSHIP", "PROJECT", "TECH_STACK");

    public InterviewPlan plan(AnalysisView analysis, int questionCount) {
        return plan(analysis, questionCount, null);
    }

    /**
     * @param fit 岗位匹配结果；为 null 时是纯简历面试，选点规则与加 JD 之前完全一致
     */
    public InterviewPlan plan(AnalysisView analysis, int questionCount, JobFit fit) {
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
        List<Candidate> focus = all.stream().filter(c -> FOCUS_MODULES.contains(c.moduleType())).toList();
        List<Candidate> pool = focus.isEmpty() ? all : focus;

        // JD 要求的技能排最前，其次弱点优先：写得不充分的地方才值得追问
        List<Candidate> ordered = pool.stream()
                .sorted(Comparator.comparingInt((Candidate c) -> matchedJd(c, fit))
                        .thenComparingInt(c -> priority(c.point().level()))
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
            // skill 用模块名（实习经历/项目经历/专业技能），不用批注标题——
            // 批注标题是"这份简历缺什么"的点评口吻，摆在题目下面像在骂人。
            // 具体考点在 comment / quote 里，出题时模型照样能看到。
            points.add(new InterviewPlan.Point("P" + (++index), candidate.moduleTitle(),
                    difficultyOf(point.level()), 1, List.of(truncate(point.comment(), 120)),
                    point.quote(), point.comment(), point.level()));
        }
        String strategy = "围绕实习、项目与技术栈，优先考察简历中表述薄弱的点：" + points.stream()
                .map(InterviewPlan.Point::skill).limit(3).reduce((a, b) -> a + "、" + b).orElse("");
        if (fit != null && !fit.prioritySkills().isEmpty()) {
            strategy = strategy + "；按岗位 JD 优先验证：" + String.join("、", fit.prioritySkills());
        }
        return new InterviewPlan(strategy, points, null, fit);
    }

    /**
     * JD 命中度：0 表示这个考察点落在 JD 要求的技能上。
     *
     * <p>用关键词包含而不是语义匹配：JD 上的技能名就是原词（Kafka、MySQL），
     * 简历批注里出现同一个词，就是"面试该验的点"。语义匹配交给 JobFitAgent，
     * 这里只做一次廉价的排序加权。</p>
     */
    private static int matchedJd(Candidate candidate, JobFit fit) {
        if (fit == null || fit.prioritySkills().isEmpty()) {
            return 1;
        }
        PointView point = candidate.point();
        String text = String.join(" ",
                candidate.moduleTitle() == null ? "" : candidate.moduleTitle(),
                point.title() == null ? "" : point.title(),
                point.quote() == null ? "" : point.quote(),
                point.comment() == null ? "" : point.comment()).toLowerCase(Locale.ROOT);
        return fit.prioritySkills().stream()
                .filter(skill -> skill != null && !skill.isBlank())
                .anyMatch(skill -> text.contains(skill.strip().toLowerCase(Locale.ROOT))) ? 0 : 1;
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
