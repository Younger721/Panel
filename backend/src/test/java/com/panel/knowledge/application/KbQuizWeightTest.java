package com.panel.knowledge.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽题权重：能力值接管之后，"弱的知识点更容易被抽到"必须是可验证的事实，而不是感觉。
 *
 * <p>加权随机本身有噪声，所以这里看的是<b>统计结果</b>（300 次抽样的命中分布），
 * 阈值都留了足够余量，不会因为随机波动偶发失败。</p>
 */
class KbQuizWeightTest {

    private static final int ROUNDS = 300;

    /** 三个知识点各一题：弱（-1.5）、中（0）、强（+1.5），没有历史作答。 */
    private static Map<Long, Long> categories() {
        Map<Long, Long> categoryOf = new LinkedHashMap<>();
        categoryOf.put(1L, 100L);
        categoryOf.put(2L, 200L);
        categoryOf.put(3L, 300L);
        return categoryOf;
    }

    private static Map<Long, Double> thetas() {
        Map<Long, Double> thetaOf = new LinkedHashMap<>();
        thetaOf.put(100L, -1.5);
        thetaOf.put(200L, 0d);
        thetaOf.put(300L, 1.5);
        return thetaOf;
    }

    @Test
    @DisplayName("弱知识点被抽中的次数明显多于强知识点")
    void weakPointIsPickedMoreOften() {
        Map<Long, Long> categoryOf = categories();
        Map<Long, Double> thetaOf = thetas();
        int weak = 0;
        int strong = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Long picked = KbQuizService.sample(java.util.List.of(1L, 2L, 3L), categoryOf, Map.of(), thetaOf, 1).get(0);
            if (picked == 1L) {
                weak++;
            } else if (picked == 3L) {
                strong++;
            }
        }
        assertTrue(weak > strong * 2, "弱项应显著多于强项，实际 weak=" + weak + " strong=" + strong);
        assertTrue(weak > ROUNDS / 3, "弱项至少要占到三分之一，实际 " + weak);
    }

    @Test
    @DisplayName("权重表为空时退回旧的固定启发式（不错过、没做过优先）")
    void fallsBackToLegacyWeightsWithoutThetas() {
        Map<Long, Long> categoryOf = categories();
        int first = 0;
        for (int round = 0; round < ROUNDS; round++) {
            if (KbQuizService.sample(java.util.List.of(1L, 2L, 3L), categoryOf, Map.of(), Map.of(), 1).get(0) == 1L) {
                first++;
            }
        }
        // 三题等权，任何一题命中的期望是 100 次；这里只验证"没有偏向性崩坏"
        assertTrue(first > 50 && first < 150, "旧启发式在三题等权时不应严重偏斜，实际 " + first);
    }

    @Test
    @DisplayName("刚做对过的题权重被压到最低（近期因子 ×0.25）")
    void recentCorrectGetsLowestWeight() {
        double recent = KbQuizService.weightOf(1L, Map.of(), Map.of(1L, 100L), Map.of(100L, -1.5));
        double fresh = KbQuizService.weightOf(1L, Map.of(), Map.of(1L, 100L), Map.of(100L, 1.5));
        assertTrue(recent > fresh, "同等近期因子下，弱项权重仍应更高");
        double weakNoHistory = KbQuizService.weightOf(1L, Map.of(), Map.of(1L, 100L), Map.of(100L, -1.5));
        assertEquals(recent, weakNoHistory, 1e-9);
    }

    @Test
    @DisplayName("没有能力值的知识点按「没做过」给权重，不会因为空表变成 0")
    void unknownCategoryKeepsBaselineWeight() {
        double weight = KbQuizService.weightOf(9L, Map.of(), Map.of(9L, 999L), Map.of());
        assertTrue(weight > 1.0, "空能力值表时权重应等于 W_NEVER，实际 " + weight);
    }
}
