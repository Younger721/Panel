package com.nova.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.ai.application.ChatModelGateway;
import com.nova.ai.domain.ContextSegment;
import com.nova.interview.agent.model.InterviewPlan;
import com.nova.interview.application.ParsedResume;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Planner：把简历能力映射成一场面试的考察点矩阵。
 *
 * <p>输出被下游强约束：题目只能来自这里的点，所以"考什么"在面试开始前就确定，
 * 而不是每轮让模型自由发挥（那样必然重复、且无法解释为什么考这个）。</p>
 */
@Component
public class PlannerAgent {

    private static final String INSTRUCTION = """
            你是面试考察点规划器。根据候选人的结构化简历，设计本次面试的考察点矩阵。

            要求：
            1. 考察点数量必须恰好等于 {turnBudget} 个。
            2. 技能必须来自简历中真实出现的技术栈，禁止虚构。
            3. 难度分布：BASIC 约 30%，ADVANCED 约 50%，EXPERT 约 20%。
            4. 每个点只需一个技能维度，不要一个点覆盖三个技能。
            5. id 用 P1、P2 依次编号，不能重复。
            6. expectedSignals 写 2-3 条"答到什么程度算合格"，用于后续评分对齐。

            只输出 JSON，不要 Markdown，不要解释：
            {"strategy":"一句话说明本次面试的整体策略",
             "points":[{"id":"P1","skill":"Kafka","difficulty":"ADVANCED","weight":2,
                        "expectedSignals":["提到分区与顺序性","能说出至少一种幂等方案"]}]}
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;

    public PlannerAgent(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public InterviewPlan plan(ParsedResume resume, int turnBudget) {
        String prompt = INSTRUCTION.replace("{turnBudget}", String.valueOf(turnBudget));
        String context = writeResume(resume);
        String raw = gateway.completeStructuredJson(prompt,
                List.of(new ContextSegment("resume", "resume", context))).content();
        InterviewPlan plan = AgentJson.parse(objectMapper, raw, InterviewPlan.class, "Planner");
        if (plan.points().size() != turnBudget) {
            throw new IllegalStateException("Planner returned " + plan.points().size()
                    + " points, expected " + turnBudget);
        }
        return plan;
    }

    private String writeResume(ParsedResume resume) {
        try {
            return objectMapper.writeValueAsString(resume);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize resume", ex);
        }
    }
}
