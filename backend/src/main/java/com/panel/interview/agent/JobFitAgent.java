package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.agent.model.JobFit;
import com.panel.interview.application.ResumeAnalysisService.AnalysisView;
import com.panel.interview.application.ResumeAnalysisService.ModuleView;
import com.panel.interview.application.ResumeAnalysisService.PointView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 岗位匹配：把简历批注和岗位 JD 对照，产出"匹配什么、缺什么、接下来最该验证什么"。
 *
 * <p>这是唯一一个<b>直接消费 JD</b> 的 agent。它的产出有两个去处：
 * 报告里的匹配卡片，以及 {@link PlannerAgent} 的选题权重——JD 上写的技能优先问。
 * 没有 JD 时整个 agent 不运行，行为与之前完全一致。</p>
 */
@Component
public class JobFitAgent {

    private static final String INSTRUCTION = """
            你是招聘顾问。把候选人的简历批注和岗位 JD 对照，回答一个问题：这份简历能不能对上这个岗位。
            只输出 JSON：
            {"headline":"一句话结论，20 字以内",
             "score":0,
             "matched":[{"skill":"JD 里的技能名","evidence":"简历中对应的原文"}],
             "gaps":[{"skill":"JD 要求但简历里看不到的技能","why":"为什么算缺口"}],
             "prioritySkills":["接下来面试最该验证的技能"]}

            规则：
            1. matched 的 evidence 必须能在简历批注里找到出处，找不到就不要列——宁可少列。
            2. gaps 只列 JD 明确要求的，不列"最好有"的。
            3. prioritySkills 最多 4 个，用 JD 里的原词，按重要程度从高到低排。
            4. score 是覆盖度 0-100，宁低勿高。
            """;

    private final InterviewAgentCall agentCall;
    private final ObjectMapper objectMapper;

    public JobFitAgent(InterviewAgentCall agentCall, ObjectMapper objectMapper) {
        this.agentCall = agentCall;
        this.objectMapper = objectMapper;
    }

    public JobFit analyze(String jd, AnalysisView analysis) {
        String raw = agentCall.structuredJson(InterviewAgentRole.JOB_FIT, INSTRUCTION, List.of(
                new ContextSegment("jd", "jd", clip(jd, 4000)),
                new ContextSegment("resume", "resume", digest(analysis)))).content();
        return AgentJson.parse(objectMapper, raw, JobFit.class, "JobFit");
    }

    /** 简历批注压成文本：模块 → 小点（程度 + 原文 + 点评）。 */
    private static String digest(AnalysisView analysis) {
        StringBuilder builder = new StringBuilder();
        for (ModuleView module : analysis.modules()) {
            builder.append('[').append(module.type()).append("] ").append(module.title()).append('\n');
            for (PointView point : module.points()) {
                builder.append("- ").append(point.title()).append('（').append(point.level()).append('）');
                if (point.quote() != null && !point.quote().isBlank()) {
                    builder.append(" 原文：").append(clip(point.quote(), 120));
                }
                if (point.comment() != null && !point.comment().isBlank()) {
                    builder.append(" 点评：").append(clip(point.comment(), 120));
                }
                builder.append('\n');
            }
        }
        return clip(builder.toString(), 4000);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
