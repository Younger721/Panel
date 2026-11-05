package com.nova.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.ai.application.ChatModelGateway;
import com.nova.ai.domain.ContextSegment;
import com.nova.interview.agent.model.InterviewPlan;
import com.nova.interview.agent.model.QuestionDraft;
import com.nova.interview.domain.model.InterviewTurn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interviewer：从考察点矩阵里挑一个<b>未覆盖</b>的点出题。
 *
 * <p>{@code pointId} 会被代码校验：必须是计划里的点、且没问过。模型想"自由发挥"
 * 就会被拒绝，这是结构约束而不是提示词约束。</p>
 */
@Component
public class InterviewerAgent {

    private static final String INSTRUCTION = """
            你是资深技术面试官。根据考察点矩阵和已问过的题目，出下一道题。

            要求：
            1. 只能从"尚未覆盖的考察点"中选择一个，把它的 id 填进 pointId。
            2. 题目必须是完整问句，能一句话听懂，不要一次问三个问题。
            3. 难度与考察点声明的 difficulty 一致。
            4. followUps 给 0-2 条追问，每条也是完整问句，用于候选人答得浅时深入。
            5. 禁止与"已问过的题目"语义重复。
            6. intent 用一句话说明这道题想验证什么。

            只输出 JSON：
            {"question":"...","type":"PROJECT|BASIC|SCENARIO","difficulty":"BASIC|ADVANCED|EXPERT",
             "pointId":"P2","intent":"...","followUps":["..."]}
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;

    public InterviewerAgent(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public QuestionDraft next(InterviewPlan plan, List<InterviewTurn> answered) {
        Set<String> covered = answered.stream()
                .map(InterviewTurn::getPointId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        List<InterviewPlan.Point> available = plan.points().stream()
                .filter(p -> !covered.contains(p.id()))
                .toList();
        if (available.isEmpty()) {
            throw new IllegalStateException("no uncovered interview point left");
        }
        String context = """
                考察点矩阵：%s
                尚未覆盖的点：%s
                已问过：%s
                """.formatted(toJson(plan), available.stream().map(InterviewPlan.Point::id).toList(),
                answered.stream().map(t -> "第" + t.getSeq() + "题：" + t.getQuestion()).toList());
        String raw = gateway.completeStructuredJson(INSTRUCTION,
                List.of(new ContextSegment("plan", "plan", context))).content();
        QuestionDraft draft = AgentJson.parse(objectMapper, raw, QuestionDraft.class, "Interviewer");
        if (draft.pointId() == null || plan.point(draft.pointId()) == null) {
            throw new IllegalStateException("Interviewer picked unknown point: " + draft.pointId());
        }
        if (covered.contains(draft.pointId())) {
            throw new IllegalStateException("Interviewer repeated a covered point: " + draft.pointId());
        }
        if (draft.question() == null || draft.question().isBlank()) {
            throw new IllegalStateException("Interviewer returned an empty question");
        }
        return draft;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize interview plan", ex);
        }
    }
}
