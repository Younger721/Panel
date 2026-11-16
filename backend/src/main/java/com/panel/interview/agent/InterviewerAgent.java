package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.agent.model.InterviewPlan;
import com.panel.interview.agent.model.QuestionDraft;
import com.panel.interview.domain.model.InterviewTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interviewer：出主题，也出追问。
 *
 * <p>两条硬约束：{@code pointId} 必须来自计划、且主题不能重复考同一个点。
 * 违反不会让整场面试崩掉——带"上次错在哪"重试一次，仍不合规就走规则兜底题。
 * 多 agent 串联时最怕的就是一个环节偶发失败把整条流程带走，这里的重试 + 降级
 * 是"失败可恢复"的落地。</p>
 */
@Component
public class InterviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(InterviewerAgent.class);

    /** 出题最多试几次（首次 + 一次带纠错信息的重试）。 */
    private static final int MAX_ATTEMPTS = 2;

    private static final String INSTRUCTION = """
            你是资深技术面试官，正在面一位候选人。根据考察点矩阵和已问过的题目，出下一道题。

            要求：
            1. 只能从"尚未覆盖的考察点"中选择一个，把它的 id 填进 pointId。
            2. 题目必须落在候选人的**实习经历、项目经历或技术栈**上。考察点里的 quote
               是他简历里的原话，必须拿它当锚点，问"这句话背后到底做了什么"，
               而不是问这类技术在任何一本教材里都有的通用答案。
            3. 问细节和判断，不要问名词解释。好问题举例：
               "你说的这个异步改造，具体是把哪一段挪出去了？改完之后接口耗时从多少降到多少？"
               "这个方案上线后遇到过什么问题？如果重做一次你会换掉哪个选择？"
            4. 简历里没写的技术不要凭空考。候选人没提过的框架、没做过的场景，
               即使跟考察点相关也不要问——那考的是他的知识面，不是他的经历。
            5. 题目必须是完整问句，一次只问一件事，能一句话听懂，不要连问三个问题。
            6. 难度与考察点声明的 difficulty 一致。
            7. 禁止与"已问过的题目"语义重复。
            8. intent 用一句话说明这道题想验证什么。
            9. 题干控制在 70 字以内（含标点）。题面是"一件事+一个要求"，不是背景介绍：
               把简历原话当锚点时引用一小段就够，不要把整条经历复述一遍。
               超过 70 字的题目请重写短一些再输出。

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
            6. 追问同样控制在 70 字以内（含标点）：先一句点出他没答到的地方，
               再给出一个具体的追问方向。不要复述他上一轮的回答。

            只输出 JSON：
            {"question":"...","intent":"..."}
            """;

    private static final String FOLLOW_UP_FALLBACK =
            "你刚才讲的这部分我还没听出具体做法。能举一个具体的例子，说清楚你当时怎么做的、结果如何吗？";

    private final InterviewAgentCall agentCall;
    private final ObjectMapper objectMapper;

    public InterviewerAgent(InterviewAgentCall agentCall, ObjectMapper objectMapper) {
        this.agentCall = agentCall;
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

        String violation = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                QuestionDraft draft = parse(INSTRUCTION, context, violation);
                check(draft, plan, covered);
                return draft;
            } catch (RuntimeException ex) {
                violation = ex.getMessage();
                log.warn("Interviewer 第 {} 次出题被拒：{}", attempt, violation);
            }
        }
        log.warn("Interviewer 连续 {} 次出题失败，降级为规则兜底题", MAX_ATTEMPTS);
        return fallback(available.get(0));
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

        String violation = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                QuestionDraft draft = parse(FOLLOW_UP_INSTRUCTION, context, violation);
                checkFollowUp(draft, chain);
                return new QuestionDraft(draft.question(), "FOLLOW_UP", point.difficulty(), point.id(),
                        draft.intent(), List.of());
            } catch (RuntimeException ex) {
                violation = ex.getMessage();
                log.warn("Interviewer 第 {} 次追问被拒：{}", attempt, violation);
            }
        }
        log.warn("Interviewer 连续 {} 次追问失败，降级为固定兜底追问", MAX_ATTEMPTS);
        return new QuestionDraft(FOLLOW_UP_FALLBACK, "FOLLOW_UP", point.difficulty(), point.id(),
                "追问失败兜底：逼候选人给一个具体例子", List.of());
    }

    private QuestionDraft parse(String instruction, String context, String violation) {
        List<ContextSegment> segments = new ArrayList<>();
        segments.add(new ContextSegment("plan", "plan", context));
        if (violation != null) {
            segments.add(new ContextSegment("repair", "repair", """
                    上一次输出被拒绝：%s
                    严格按前面的约束重新输出，点 id 必须是"尚未覆盖的点"中的一个，问题必须是非空完整问句。
                    """.formatted(violation)));
        }
        String raw = agentCall.structuredJson(InterviewAgentRole.QUESTION, instruction,
                List.copyOf(segments)).content();
        return AgentJson.parse(objectMapper, raw, QuestionDraft.class, "Interviewer");
    }

    private static void check(QuestionDraft draft, InterviewPlan plan, Set<String> covered) {
        if (draft.pointId() == null || plan.point(draft.pointId()) == null) {
            throw new IllegalStateException("pointId 不在考察点矩阵里：" + draft.pointId());
        }
        if (covered.contains(draft.pointId())) {
            throw new IllegalStateException("pointId 已经问过：" + draft.pointId());
        }
        if (draft.question() == null || draft.question().isBlank()) {
            throw new IllegalStateException("question 为空");
        }
    }

    private static void checkFollowUp(QuestionDraft draft, List<InterviewTurn> chain) {
        if (draft.question() == null || draft.question().isBlank()) {
            throw new IllegalStateException("追问为空");
        }
        if (chain.stream().anyMatch(t -> t.getQuestion().equals(draft.question()))) {
            throw new IllegalStateException("追问与之前的问题完全相同");
        }
    }

    /** 兜底题：不追求巧思，只保证"基于真实经历、能继续追问"。 */
    private static QuestionDraft fallback(InterviewPlan.Point point) {
        String quote = point.quote() == null || point.quote().isBlank() ? point.skill() : point.quote().strip();
        return new QuestionDraft(
                "你在简历里写到「" + quote + "」，能具体讲讲这一段里你负责的部分、当时最难的地方，最后是怎么解决的吗？",
                "PROJECT", point.difficulty(), point.id(),
                "出题失败兜底：验证「" + point.skill() + "」是否是真实经历", List.of());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize interview plan", ex);
        }
    }
}
