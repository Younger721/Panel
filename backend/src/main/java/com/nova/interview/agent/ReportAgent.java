package com.nova.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.ai.application.ChatModelGateway;
import com.nova.ai.domain.ContextSegment;
import com.nova.interview.agent.model.InterviewReportData;
import com.nova.interview.domain.model.InterviewTurn;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ReportAgent：把逐题结果汇总成一份报告。
 *
 * <p>只负责"写"，不负责"算"：总分由 {@code InterviewService} 用逐题分数重算，
 * 技能分也以逐题记录为准。模型给的 overallScore 一律丢弃——
 * 这是参考实现里最值得抄的一条防幻觉措施。</p>
 */
@Component
public class ReportAgent {

    private static final String INSTRUCTION = """
            你是面试复盘专家。根据本场面试的逐题问答与评分，写一份候选人报告。

            要求：
            1. summary 200 字以内，说清整体水平、最强项、最短板。
            2. strengths 与 improvements 各 3-5 条，必须具体到技术点，不要"沟通良好"这类空话。
            3. skillScores 按技能聚合，score 用该技能各题得分的均值（整数），comment 一句话点评。
            4. 不要编造逐题记录里没有的信息。
            5. 不要给总分——总分由系统计算。

            只输出 JSON：
            {"summary":"...","strengths":["..."],"improvements":["..."],
             "skillScores":[{"skill":"Kafka","score":62,"comment":"..."}]}
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;

    public ReportAgent(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public InterviewReportData report(List<InterviewTurn> turns) {
        StringBuilder context = new StringBuilder("逐题记录：\n");
        for (InterviewTurn turn : turns) {
            context.append("第").append(turn.getSeq()).append("题 [").append(turn.getSkill()).append("] ")
                    .append(turn.getQuestion()).append('\n')
                    .append("回答：").append(turn.getAnswer() == null ? "(未作答)" : turn.getAnswer()).append('\n')
                    .append("得分：").append(turn.getFinalScore()).append('\n')
                    .append("评语：").append(turn.getFeedback()).append("\n\n");
        }
        String raw = gateway.completeStructuredJson(INSTRUCTION,
                List.of(new ContextSegment("report", "report", context.toString()))).content();
        return AgentJson.parse(objectMapper, raw, InterviewReportData.class, "Report");
    }
}
