package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbSkillState;
import com.panel.knowledge.infrastructure.persistence.KbCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbSkillStateStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力值服务：把作答信号写进 {@code kb_skill_state}，并按树聚合出弱项。
 *
 * <p>写入口只有一个 {@link #record}，刷题和面试都走它——区别只在 {@code interview} 标志
 * （面试权重 ×2）。读出口 {@link #skills} 给前端"弱项面板"用。</p>
 */
@Service
public class KbSkillService {

    private static final int MAX_POINTS = 60;

    private final KbSkillStateStore store;
    private final KbCategoryStore categories;

    public KbSkillService(KbSkillStateStore store, KbCategoryStore categories) {
        this.store = store;
        this.categories = categories;
    }

    /**
     * 记一次观测。
     *
     * @param difficulty 题目难度原文（"中"/"3"/"HARD" 都行，认不出来按中等）
     * @param interview  是否面试信号（权重 ×2）
     */
    @Transactional
    public void record(Long userId, Long kbId, Long categoryId, String result, String difficulty,
                       boolean interview) {
        if (userId == null || kbId == null || categoryId == null || result == null) {
            return; // 没挂知识点的题进不了能力值：没有归属就没有"哪个知识点弱"这一说
        }
        KbSkillState state = store.find(userId, kbId, categoryId);
        Instant now = Instant.now();
        if (state == null) {
            state = KbSkillState.create(userId, kbId, categoryId);
        }
        double b = KbSkillModel.difficultyB(difficulty);
        double observation = KbSkillModel.observationOf(result);
        double weight = KbSkillModel.weightOf(result, interview);
        double next = KbSkillModel.nextTheta(state.theta(), b, observation, weight, state.attempts());
        state.apply(next, result.trim().toUpperCase(java.util.Locale.ROOT),
                interview ? KbSkillState.Source.INTERVIEW : KbSkillState.Source.QUIZ, now);
        store.save(state);
    }

    /** 给抽题用的轻量视图：知识点 → 衰减后的能力值。 */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Double> effectiveThetas(Long kbId, Long userId) {
        java.util.Map<Long, Double> result = new HashMap<>();
        Instant now = Instant.now();
        for (KbSkillState state : store.findByKb(userId, kbId)) {
            result.put(state.categoryId(), KbSkillModel.effectiveTheta(state.theta(), state.lastSeenAt(), now));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public KbQuizViews.Skills skills(Long kbId, Long userId) {
        List<KbSkillState> states = store.findByKb(userId, kbId);
        if (states.isEmpty()) {
            return new KbQuizViews.Skills(kbId, 0, List.of(), List.of());
        }
        Map<Long, KbCategory> tree = new HashMap<>();
        for (KbCategory category : categories.findByKb(kbId)) {
            tree.put(category.getId(), category);
        }
        Set<Long> parents = new HashSet<>();
        for (KbCategory category : tree.values()) {
            if (category.parentId() != null && category.parentId() != KbCategory.ROOT_PARENT_ID) {
                parents.add(category.parentId());
            }
        }
        Instant now = Instant.now();
        Map<Long, KbSkillState> byCategory = new HashMap<>();
        List<KbQuizViews.SkillPoint> points = new ArrayList<>();
        for (KbSkillState state : states) {
            byCategory.put(state.categoryId(), state);
            if (parents.contains(state.categoryId())) {
                continue; // 有子节点的节点不当作叶子展示，等下面聚合
            }
            points.add(point(state, tree, now));
        }
        points.sort((left, right) -> Double.compare(left.thetaEffective(), right.thetaEffective()));
        if (points.size() > MAX_POINTS) {
            points = points.subList(0, MAX_POINTS);
        }
        return new KbQuizViews.Skills(kbId, states.size(), points, branches(tree, parents, byCategory, now));
    }

    /** 父节点：子能力值按"作答次数"加权平均，题做得多的子节点说话更响。 */
    private List<KbQuizViews.SkillPoint> branches(Map<Long, KbCategory> tree, Set<Long> parents,
                                                 Map<Long, KbSkillState> byCategory, Instant now) {
        List<KbQuizViews.SkillPoint> result = new ArrayList<>();
        for (Long parentId : parents) {
            KbCategory node = tree.get(parentId);
            if (node == null) {
                continue;
            }
            double weighted = 0d;
            double weightSum = 0d;
            int attempts = 0;
            int correct = 0;
            int wrong = 0;
            int unsure = 0;
            int interview = 0;
            Instant lastSeen = null;
            int tracked = 0;
            for (Long childId : childrenOf(tree, parentId)) {
                KbSkillState state = byCategory.get(childId);
                if (state == null) {
                    continue;
                }
                tracked++;
                double weight = Math.max(1, state.attempts());
                weighted += KbSkillModel.effectiveTheta(state.theta(), state.lastSeenAt(), now) * weight;
                weightSum += weight;
                attempts += state.attempts();
                correct += state.correctCount();
                wrong += state.wrongCount();
                unsure += state.unsureCount();
                interview += state.interviewCount();
                if (lastSeen == null || state.lastSeenAt().isAfter(lastSeen)) {
                    lastSeen = state.lastSeenAt();
                }
            }
            if (tracked == 0 || weightSum <= 0) {
                continue;
            }
            double theta = weighted / weightSum;
            result.add(new KbQuizViews.SkillPoint(parentId, node.name(), pathOf(node, tree), node.depth(), false,
                    attempts, correct, wrong, unsure, interview, round(theta), round(theta),
                    round(KbSkillModel.wrongRate(theta, 0d)), null, lastSeen));
        }
        result.sort((left, right) -> Double.compare(left.thetaEffective(), right.thetaEffective()));
        return result;
    }

    private KbQuizViews.SkillPoint point(KbSkillState state, Map<Long, KbCategory> tree, Instant now) {
        KbCategory node = tree.get(state.categoryId());
        double theta = state.theta();
        double effective = KbSkillModel.effectiveTheta(theta, state.lastSeenAt(), now);
        return new KbQuizViews.SkillPoint(state.categoryId(),
                node == null ? "知识点 #" + state.categoryId() : node.name(),
                node == null ? null : pathOf(node, tree),
                node == null ? 0 : node.depth(),
                true,
                state.attempts(), state.correctCount(), state.wrongCount(), state.unsureCount(),
                state.interviewCount(), round(theta), round(effective),
                round(KbSkillModel.wrongRate(effective, 0d)), state.lastResult(), state.lastSeenAt());
    }

    private static List<Long> childrenOf(Map<Long, KbCategory> tree, Long parentId) {
        List<Long> result = new ArrayList<>();
        for (KbCategory category : tree.values()) {
            if (parentId.equals(category.parentId())) {
                result.add(category.getId());
            }
        }
        return result;
    }

    static String pathOf(KbCategory node, Map<Long, KbCategory> tree) {
        List<String> names = new ArrayList<>();
        KbCategory cursor = node;
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            names.add(cursor.name());
            Long parentId = cursor.parentId();
            cursor = parentId == null || parentId == KbCategory.ROOT_PARENT_ID ? null : tree.get(parentId);
        }
        java.util.Collections.reverse(names);
        return String.join(" / ", names);
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
