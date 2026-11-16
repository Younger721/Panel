package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.agent.model.ReviewDecision;
import com.panel.interview.agent.model.TurnEvaluation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reviewer：只做一致性仲裁，不重新独立打分。
 *
 * <p>由 {@code InterviewService} 在两种"可疑"情况下调用：</p>
 * <ul>
 *   <li>Evaluator 没给出任何引用原文的证据（评分无依据）</li>
 *   <li>回答明显有内容、却被判为未作答或全 0（自相矛盾）</li>
 * </ul>
 *
 * <p>正常回答不走这一步——否则每轮多一次模型调用，成本翻倍而信息增量很小。</p>
 */
@Component
public class ReviewerAgent {

    private static final String INSTRUCTION = """
            你是评分复核员。给定一道题、候选人的回答、以及另一位面试官的评分结果，
            判断评分与证据是否矛盾。你只做两件事：确认或修正，不重新独立打一套分。

            允许修正的情形（仅限这些）：
            1. 评分引用的 quote 在候选人回答中<b>不存在</b>（编造证据）。
            2. 候选人明显回答了大量内容，却被判 answered=false 或全 0 分。
            3. 某一维度分数与 evidence 描述的能力明显不符（例如证据显示讲清了原理，depth 却给 10 分）。

            不允许修正的情形：你只是"觉得应该更高/更低"，但没有具体矛盾。

            只输出 JSON：
            {"adjusted":false,
             "adjustments":[{"axis":"depth","from":10,"to":40,"why":"证据显示候选人讲到了分区顺序性"}],
             "reason":"一句话说明结论"}
            """;

    private final InterviewAgentCall agentCall;
    private final ObjectMapper objectMapper;

    public ReviewerAgent(InterviewAgentCall agentCall, ObjectMapper objectMapper) {
        this.agentCall = agentCall;
        this.objectMapper = objectMapper;
    }

    public ReviewDecision review(String question, String answer, TurnEvaluation evaluation) {
        String context = """
                题目：%s
                候选人回答：
                %s

                待复核的评分：
                %s
                """.formatted(question, answer, write(evaluation));
        String raw = agentCall.structuredJson(InterviewAgentRole.REVIEW, INSTRUCTION,
                List.of(new ContextSegment("review", "review", context))).content();
        return AgentJson.parse(objectMapper, raw, ReviewDecision.class, "Reviewer");
    }

    private String write(TurnEvaluation evaluation) {
        try {
            return objectMapper.writeValueAsString(evaluation);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize evaluation", ex);
        }
    }
}
