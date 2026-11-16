package com.panel.interview.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.panel.knowledge.application.KbStudyMaterialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「这场面试我哪里弱，接下来去学什么」。
 *
 * <p>面试和知识库原来是两条线：面试考出来的弱项只进了一份报告，
 * 刷题和文档在另一边，中间断着。这里把断点接上——报告里的 skillScores
 * 低于阈值就换算成一组复习材料（概念 / 题目 / 文档）。</p>
 *
 * <p>只做"找材料"，不做"排计划"：学习路径要不要每天几题、几天学完，
 * 依赖用户的可用时间，那是产品决策，不该由一次模型调用拍板。</p>
 */
@Service
public class InterviewStudyPlanService {

    private static final Logger log = LoggerFactory.getLogger(InterviewStudyPlanService.class);

    /**
     * 低于这个分算"弱项"，默认 60 —— 项目里强弱分界的既有口径（报告和改进建议都按它分）。
     *
     * <p>做成可配置是为了留一个"严格模式"的口子：想看知识库匹配效果时把阈值调高，
     * 就能让每场面试都产出计划；线上按默认走，不给答得还行的人塞一堆"补课"。</p>
     */
    private static final int DEFAULT_WEAK_THRESHOLD = 60;

    /** 一次最多给三个方向：给十个方向等于没给方向。 */
    private static final int MAX_SKILLS = 3;

    /** 每个方向给几样材料 */
    private static final int PER_SKILL_LIMIT = 3;

    private final InterviewService interviews;
    private final KbStudyMaterialService catalog;
    private final int weakThreshold;

    public InterviewStudyPlanService(InterviewService interviews, KbStudyMaterialService catalog,
                                     @Value("${panel.interview.study-plan.weak-threshold:60}")
                                     int weakThreshold) {
        this.interviews = interviews;
        this.catalog = catalog;
        this.weakThreshold = weakThreshold <= 0 || weakThreshold > 100 ? DEFAULT_WEAK_THRESHOLD : weakThreshold;
    }

    public record WeakSkill(String skill, int score) {
    }

    public record StudyPlan(List<WeakSkill> weakSkills, List<KbStudyMaterialService.Material> materials,
                            int threshold) {
    }

    /**
     * 报告里的弱项 → 知识库里的材料。
     *
     * <p>会话没结束或报告还没生成时 {@code interviews.report} 会抛 409，
     * 这里不吞——"现在还没有学习计划"本身就是准确回答。</p>
     */
    public StudyPlan plan(Long sessionId) {
        JsonNode report = interviews.report(sessionId);
        // report_json 存的是 {"report": {...}}，skillScores 在里面一层；
        // 也兼容没有 wrapper 的老数据，免得读到空就静默返回空计划。
        JsonNode scores = report.path("skillScores");
        if (!scores.isArray()) {
            scores = report.path("report").path("skillScores");
        }

        List<WeakSkill> weak = new ArrayList<>();
        if (scores.isArray()) {
            for (JsonNode node : scores) {
                int score = node.path("score").asInt(Integer.MAX_VALUE);
                if (score < weakThreshold) {
                    weak.add(new WeakSkill(node.path("skill").asText("综合"), score));
                }
            }
        }
        // 越弱越靠前：只给前三个方向，先补最疼的那块
        weak.sort(Comparator.comparingInt(WeakSkill::score));
        List<WeakSkill> top = weak.stream().limit(MAX_SKILLS).toList();
        List<KbStudyMaterialService.Material> materials = top.stream()
                .map(skill -> catalog.findFor(skill.skill(), PER_SKILL_LIMIT))
                .toList();
        log.info("Study plan for session {}: weak skills={}, materials={}", sessionId,
                top.stream().map(WeakSkill::skill).toList(), materials.size());
        return new StudyPlan(top, materials, weakThreshold);
    }
}
