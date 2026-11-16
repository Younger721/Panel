package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.agent.model.InterviewReportData;
import com.panel.interview.domain.model.InterviewTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ReportAgent：把逐题结果汇总成一份报告。
 *
 * <p>只负责"写"，不负责"算"：总分由 {@code InterviewService} 用逐题分数重算，
 * 技能分也以逐题记录为准。模型给的 overallScore 一律丢弃——
 * 这是参考实现里最值得抄的一条防幻觉措施。</p>
 *
 * <p>报告是整场面试的最后一跳：模型输出不可解析时退化成按逐题分数汇总的规则报告，
 * 而不是抛异常。抛出去的后果是会话永远停在 IN_PROGRESS——历史页一直显示"进行中"、
 * 报告接口永远 409，用户已花掉的十几次模型调用全部作废。</p>
 */
@Component
public class ReportAgent {

    private static final Logger log = LoggerFactory.getLogger(ReportAgent.class);

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

    private final InterviewAgentCall agentCall;
    private final ObjectMapper objectMapper;

    /** 流式总评：短、纯文本、能先出字。和下面的结构化报告是同一份数据的两种切法。 */
    private static final String SUMMARY_INSTRUCTION = """
            你是面试复盘专家。根据下面这场面试的逐题得分与评语，写一段 150 字以内的总评：
            说清整体水平、最强项、最短板。

            直接输出这段文字，不要 JSON、不要分点、不要"综上所述"这类收尾套话。
            """;

    public ReportAgent(InterviewAgentCall agentCall, ObjectMapper objectMapper) {
        this.agentCall = agentCall;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式总评。
     *
     * <p>只喂「技能 + 得分 + 评语」，不喂回答原文——输入小、出字快，争取在
     * 完整报告（结构化 JSON，20~40 秒）之前先给用户一个结论。缺点是对细节的
     * 感知不如全文，所以它只负责 summary 这一段，分项评价仍由 {@link #report} 产出。</p>
     *
     * @return 完整总评；失败返回空串，调用方退回结构化报告里的 summary
     */
    public String streamSummary(List<InterviewTurn> turns, Consumer<String> onChunk) {
        StringBuilder context = new StringBuilder("逐题记录：\n");
        for (InterviewTurn turn : turns) {
            if (turn.getFinalScore() == null) {
                continue;
            }
            context.append("第").append(turn.getSeq()).append("题 [").append(turn.getSkill()).append("] ")
                    .append(turn.getQuestion()).append('\n')
                    .append("得分：").append(turn.getFinalScore()).append('\n')
                    .append("评语：").append(turn.getFeedback()).append("\n\n");
        }
        try {
            String text = agentCall.streamText(InterviewAgentRole.REPORT, SUMMARY_INSTRUCTION,
                    List.of(new ContextSegment("report-summary", "report-summary", context.toString())),
                    onChunk).content();
            return text == null ? "" : text.strip();
        } catch (RuntimeException ex) {
            log.warn("Report summary streaming failed, falling back to structured summary: {}", ex.toString());
            return "";
        }
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
        String raw = agentCall.structuredJson(InterviewAgentRole.REPORT, INSTRUCTION,
                List.of(new ContextSegment("report", "report", context.toString()))).content();
        try {
            return AgentJson.parse(objectMapper, raw, InterviewReportData.class, "Report");
        } catch (RuntimeException ex) {
            log.warn("ReportAgent output unparsable, degrading to rule-based report: {}", ex.toString());
            return fallback(turns);
        }
    }

    /** 规则报告：只用库里已有的逐题分数，不编造任何新信息。 */
    private static InterviewReportData fallback(List<InterviewTurn> turns) {
        List<InterviewTurn> scored = turns.stream()
                .filter(turn -> turn.getFinalScore() != null)
                .toList();
        int average = (int) Math.round(scored.stream()
                .mapToInt(InterviewTurn::getFinalScore)
                .average()
                .orElse(0));
        String summary = scored.isEmpty()
                ? "本场没有有效作答记录，只能给出逐题原始评分。"
                : "本场共 " + turns.size() + " 题、有效作答 " + scored.size() + " 题，平均 " + average
                + " 分。报告由自动汇总生成（模型报告本次不可用），强弱项请参考逐题评语。";
        return new InterviewReportData(summary, strengthsOf(scored), weaknessesOf(scored), skillAggregate(scored));
    }

    private static List<InterviewReportData.SkillScore> skillAggregate(List<InterviewTurn> scored) {
        Map<String, List<Integer>> bySkill = new LinkedHashMap<>();
        for (InterviewTurn turn : scored) {
            String skill = turn.getSkill() == null || turn.getSkill().isBlank() ? "综合" : turn.getSkill();
            bySkill.computeIfAbsent(skill, key -> new ArrayList<>()).add(turn.getFinalScore());
        }
        List<InterviewReportData.SkillScore> scores = new ArrayList<>();
        bySkill.forEach((skill, values) -> {
            int avg = (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
            scores.add(new InterviewReportData.SkillScore(skill, avg,
                    "自动汇总：" + values.size() + " 题平均 " + avg + " 分"));
        });
        return scores;
    }

    private static List<String> strengthsOf(List<InterviewTurn> scored) {
        return scored.stream()
                .filter(turn -> turn.getFinalScore() >= 70)
                .sorted(Comparator.comparingInt(InterviewTurn::getFinalScore).reversed())
                .limit(3)
                .map(turn -> (turn.getSkill() == null ? "综合" : turn.getSkill()) + "：" + turn.getFinalScore() + " 分")
                .toList();
    }

    private static List<String> weaknessesOf(List<InterviewTurn> scored) {
        return scored.stream()
                .filter(turn -> turn.getFinalScore() < 60)
                .sorted(Comparator.comparingInt(InterviewTurn::getFinalScore))
                .limit(3)
                .map(turn -> (turn.getSkill() == null ? "综合" : turn.getSkill()) + "：" + turn.getFinalScore() + " 分")
                .toList();
    }
}
