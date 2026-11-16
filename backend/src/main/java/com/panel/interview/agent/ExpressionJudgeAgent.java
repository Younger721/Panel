package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.agent.model.TurnEvaluation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表达评委：和 {@link EvaluatorAgent} 并行、但<b>上下文完全隔离</b>的第二位评委。
 *
 * <p>存在的理由不是"多加一个模型显得高级"，而是评分目标互斥：
 * 技术评委关心"说得对不对"，容易因为术语密集而给出偏高的表达分；
 * 表达评委只看"听的人能不能跟上"，不看技术对错。两份分数摆在一起，
 * 候选人才知道自己是"懂但讲不清"还是"讲得漂亮但没内容"——这是单评委给不出的信息。</p>
 *
 * <p>复用 {@link TurnEvaluation} 的形状（四个维度），只是为了复用解析与展示，
 * 四个维度的语义由本类的 prompt 重新定义，并不与技术评委一致。</p>
 */
@Component
public class ExpressionJudgeAgent {

    private static final String INSTRUCTION = """
            你是面试的"表达与结构"评委。你不判断技术对错——技术对错由另一位评委负责，
            你也看不到他的评分。你只回答一个问题：这段回答，听的人能不能跟上？

            四个维度（各 0-100，含义与技术评委不同，请按下面定义打）：
            - accuracy 表述准确（不是技术准确）：有没有含糊其辞、有没有把话说成模棱两可
            - depth 具体程度：是否给出具体事实（数字、时间、角色、结果），而不是空泛形容词
            - completeness 叙事完整：背景 → 我做了什么 → 结果，三段落是否齐全
            - clarity 结构清晰：结论先行、有条理、没有绕圈子

            硬性要求：
            1. evidence 至少一条，quote 必须是从候选人回答里<b>原样复制</b>的片段（不得改写）。
            2. 只评价表达，不评价技术方案的优劣；「这个方案不对」不属于你的职责。
            3. 回答是"不知道/没做过"这类时：四个维度全给 0，answered 填 false。
            4. 不要给安慰分：通顺但空洞的回答，depth 与 completeness 应当很低。
            5. feedback 控制在 100 字内，只说表达上的一个具体优点和一个具体毛病。

            只输出 JSON：
            {"scores":{"accuracy":0,"depth":0,"completeness":0,"clarity":0},
             "evidence":[{"quote":"原文片段","note":"这段好/差在哪"}],
             "deductions":["表达上的问题"],
             "feedback":"...",
             "answered":true}
            """;

    private final InterviewAgentCall agentCall;
    private final ObjectMapper objectMapper;

    public ExpressionJudgeAgent(InterviewAgentCall agentCall, ObjectMapper objectMapper) {
        this.agentCall = agentCall;
        this.objectMapper = objectMapper;
    }

    public TurnEvaluation evaluate(String question, String skill, String answer) {
        String context = """
                题目：%s
                考察技能：%s
                候选人回答：
                %s
                """.formatted(question, skill, answer);
        String raw = agentCall.structuredJson(InterviewAgentRole.EXPRESSION_JUDGE, INSTRUCTION,
                List.of(new ContextSegment("turn", "turn", context))).content();
        TurnEvaluation evaluation = AgentJson.parse(objectMapper, raw, TurnEvaluation.class, "ExpressionJudge");
        if (evaluation.scores() == null) {
            throw new IllegalStateException("ExpressionJudge returned no scores");
        }
        return evaluation;
    }
}
