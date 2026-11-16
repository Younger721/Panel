package com.panel.knowledge.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力模型的口径测试：这些数字是产品行为（抽题权重、弱项排序）的地基，
 * 改参数前先看这里的断言是不是还成立。
 */
class KbSkillModelTest {

    @Test
    @DisplayName("答错一道「该会」的题，扣分明显多于答对一道简单题加的分")
    void wrongHurtsMoreThanEasyCorrect() {
        double start = 1.0;                 // 自认为不错
        double easy = KbSkillModel.difficultyB("简");
        double hard = KbSkillModel.difficultyB("难");

        double afterCorrectEasy = KbSkillModel.nextTheta(start, easy, 1d, 1d, 5);
        double afterWrongEasy = KbSkillModel.nextTheta(start, easy, 0d, 1d, 5);
        double afterWrongHard = KbSkillModel.nextTheta(start, hard, 0d, 1d, 5);

        double gain = afterCorrectEasy - start;
        double lossEasy = start - afterWrongEasy;
        assertTrue(lossEasy > gain * 2, "答错简单题扣分应远大于答对简单题加分");
        assertTrue(lossEasy > start - afterWrongHard, "答错简单题比答错难题扣得更多");
    }

    @Test
    @DisplayName("做得越多，单次冲击越小（K 递减）")
    void learningRateDecaysWithAttempts() {
        double first = Math.abs(KbSkillModel.nextTheta(0d, 0d, 1d, 1d, 0));
        double tenth = Math.abs(KbSkillModel.nextTheta(0d, 0d, 1d, 1d, 10));
        assertTrue(first > tenth * 2, "第 11 次的位移应明显小于第 1 次");
    }

    @Test
    @DisplayName("面试信号权重是刷题的 2 倍")
    void interviewWeighsDouble() {
        double quiz = KbSkillModel.nextTheta(0d, 0d, 0d, KbSkillModel.weightOf("WRONG", false), 0);
        double interview = KbSkillModel.nextTheta(0d, 0d, 0d, KbSkillModel.weightOf("WRONG", true), 0);
        assertEquals(quiz * 2, interview, 1e-9);
    }

    @Test
    @DisplayName("「不确定」按半对处理，且权重减半")
    void unsureIsHalfCorrectHalfWeight() {
        assertEquals(0.5, KbSkillModel.observationOf("UNSURE"));
        assertEquals(0.5, KbSkillModel.weightOf("UNSURE", false));
    }

    @Test
    @DisplayName("久不练习向 0 回落，但最多只回落 40%")
    void decayPullsTowardZeroWithFloor() {
        Instant now = Instant.now();
        double theta = -1.6;

        double fresh = KbSkillModel.effectiveTheta(theta, now.minus(Duration.ofDays(1)), now);
        double stale = KbSkillModel.effectiveTheta(theta, now.minus(Duration.ofDays(180)), now);

        assertTrue(Math.abs(stale) < Math.abs(fresh), "放久了应该更接近不确定");
        assertEquals(theta * KbSkillModel.DECAY_FLOOR, stale, 1e-9, "衰减有 60% 下限");
    }

    @Test
    @DisplayName("难度文本脏数据不抛异常，认不出来按中等")
    void difficultyParsingIsForgiving() {
        assertEquals(0d, KbSkillModel.difficultyB(null), 1e-9);
        assertEquals(0d, KbSkillModel.difficultyB("中"), 1e-9);
        assertEquals(-1.2d, KbSkillModel.difficultyB("简单"), 1e-9);
        assertEquals(1.2d, KbSkillModel.difficultyB("HARD"), 1e-9);
        assertEquals(-2.4d, KbSkillModel.difficultyB("1"), 1e-9);
        assertEquals(2.4d, KbSkillModel.difficultyB("5"), 1e-9);
        assertEquals(0d, KbSkillModel.difficultyB("unknown-level"), 1e-9);
    }

    @Test
    @DisplayName("弱项排序：连续答错后 wrongRate 超过 50%，答对几次后回落")
    void wrongRateReflectsRecentPerformance() {
        double theta = 0d;
        for (int i = 0; i < 4; i++) {
            theta = KbSkillModel.nextTheta(theta, 0d, 0d, 1d, i);
        }
        assertTrue(KbSkillModel.wrongRate(theta, 0d) > 0.6, "连错四次应被判定为高风险");
        for (int i = 4; i < 14; i++) {
            theta = KbSkillModel.nextTheta(theta, 0d, 1d, 1d, i);
        }
        assertTrue(KbSkillModel.wrongRate(theta, 0d) < 0.4, "连续答对后风险应回落");
    }
}
