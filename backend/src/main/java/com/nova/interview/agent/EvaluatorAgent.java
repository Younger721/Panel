package com.nova.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.ai.application.ChatModelGateway;
import com.nova.ai.domain.ContextSegment;
import com.nova.interview.agent.model.TurnEvaluation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluator：逐题打分，并且必须给出回答原文里的证据片段。
 *
 * <p>"证据"不是装饰：没有 {@code evidence.quote} 的评分会被上层判定无效，
 * 从而触发 Reviewer 或直接按 0 分处理。这样分数永远能回溯到候选人说过的话。</p>
 */
@Component
public class EvaluatorAgent {

    private static final String INSTRUCTION = """
            你是资深技术面试官，负责给一道面试题的回答打分。

            评分维度（各 0-100）：
            - accuracy 准确性（权重 40%）：技术概念是否正确，有无事实错误
            - depth 深度（权重 25%）：是否触及原理、边界、权衡
            - completeness 完整性（权重 20%）：是否覆盖题目要求的核心点
            - clarity 表达（权重 15%）：逻辑是否清晰、是否答到点上

            硬性要求：
            1. evidence 至少一条，quote 必须是从候选人回答里<b>原样复制</b>的片段（不得改写、不得编造）。
            2. 每条扣分都要在 deductions 里写明理由。
            3. 如果回答是"不知道/不会/没做过/跳过"或完全没有技术内容：
               四个维度全部给 0，answered 填 false，evidence 里 quote 填那段放弃作答的原话。
            4. 不要给安慰分；空洞但通顺的回答，depth 与 completeness 应当很低。
            5. feedback 控制在 120 字内，先肯定具体优点，再指出缺失。

            只输出 JSON：
            {"scores":{"accuracy":0,"depth":0,"completeness":0,"clarity":0},
             "evidence":[{"quote":"原文片段","note":"这段说明了什么"}],
             "deductions":["扣分点"],
             "feedback":"...",
             "answered":true}
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;

    public EvaluatorAgent(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public TurnEvaluation evaluate(String question, String skill, String answer) {
        String context = """
                题目：%s
                考察技能：%s
                候选人回答：
                %s
                """.formatted(question, skill, answer);
        String raw = gateway.completeStructuredJson(INSTRUCTION,
                List.of(new ContextSegment("turn", "turn", context))).content();
        TurnEvaluation evaluation = AgentJson.parse(objectMapper, raw, TurnEvaluation.class, "Evaluator");
        if (evaluation.scores() == null) {
            throw new IllegalStateException("Evaluator returned no scores");
        }
        return evaluation;
    }
}
