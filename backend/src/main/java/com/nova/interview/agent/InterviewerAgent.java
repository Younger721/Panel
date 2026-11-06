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
 * Interviewer：出主题，也出追问。
 *
 * <p>两条硬约束：{@code pointId} 必须来自计划、且主题不能重复考同一个点；
 * 违反就直接抛异常让流程停下，而不是"尽力而为"地出一道偏题。</p>
 */
@Component
public class InterviewerAgent {

    private static final String INSTRUCTION = """
            你是资深技术面试官。根据考察点矩阵和已问过的题目，出下一道题。

            要求：
            1. 只能从"尚未覆盖的考察点"中选择一个，把它的 id 填进 pointId。
            2. 题目要贴着候选人的原文写（考察点里的 quote 是他简历里的原话），
               优先让他解释"这句话背后到底做了什么"，而不是问通用八股。
            3. 题目必须是完整问句，能一句话听懂，不要一次问三个问题。
            4. 难度与考察点声明的 difficulty 一致。
            5. 禁止与"已问过的题目"语义重复。
            6. intent 用一句话说明这道题想验证什么。

            只输出 JSON：
            {"question":"...","type":"PROJECT|BASIC|SCENARIO","difficulty":"BASIC|ADVANCED|EXPERT",
             "pointId":"P2","intent":"...","followUps":[]}
            """;

    private static final String FOLLOW_UP_INSTRUCTION = """
            你是资深技术面试官。候选人刚才的回答不够深入，你需要就"当前考察点"再追一层。

            要求：
            1. 追问必须围绕当前考察点本身，不能顺着候选人回答里提到的其他技术点转移话题。
            2. 如果候选人答非所问（回答的内容和考察点无关），先指出这一点，再把他拉回考察点追问。
            3. 只追问上次回答里含糊、缺失或明显回避的部分，不要重复已经问过的问题。
            4. 一次只问一个具体问题，要指出具体方向（例如"你说的幂等是用什么做的？
               Redis 还是数据库唯一键？并发下怎么保证？"），禁止"能详细说说吗"这类空话。
            5. 难度不超过该考察点的难度，问题要能让候选人暴露真实细节。

            只输出 JSON：
            {"question":"...","intent":"..."}
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

    /**
     * 就同一个考察点追问一层。
     *
     * @param point     当前考察点
     * @param chain     该点下已经问过的题（按 seq 升序）
     * @param lastAnswer 候选人最近一次回答
     */
    public QuestionDraft followUp(InterviewPlan.Point point, List<InterviewTurn> chain, String lastAnswer) {
        if (point == null) {
            throw new IllegalStateException("follow-up requires a point");
        }
        String context = """
                考察点：%s
                简历原文：%s
                这个点的点评（面试官本该验证什么）：%s
                这个点已经问过：%s
                候选人上一次回答：%s
                """.formatted(point.skill(), point.quote(), point.comment(),
                chain.stream().map(InterviewTurn::getQuestion).toList(), lastAnswer);
        String raw = gateway.completeStructuredJson(FOLLOW_UP_INSTRUCTION,
                List.of(new ContextSegment("followup", "followup", context))).content();
        QuestionDraft draft = AgentJson.parse(objectMapper, raw, QuestionDraft.class, "Interviewer");
        if (draft.question() == null || draft.question().isBlank()) {
            throw new IllegalStateException("Interviewer returned an empty follow-up question");
        }
        if (chain.stream().anyMatch(t -> t.getQuestion().equals(draft.question()))) {
            throw new IllegalStateException("Interviewer repeated the same question as a follow-up");
        }
        return new QuestionDraft(draft.question(), "FOLLOW_UP", point.difficulty(), point.id(),
                draft.intent(), List.of());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize interview plan", ex);
        }
    }
}
