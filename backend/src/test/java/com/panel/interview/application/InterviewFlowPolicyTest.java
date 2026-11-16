package com.panel.interview.application;

import com.panel.interview.application.InterviewFlowPolicy.Step;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 这条规则决定一场面试的节奏：该追的时候不追，面试会变浅；追过头，候选人会被卡在一个点上。
 * 之前它埋在事务方法里没有测试，现在逐个分支钉死。
 */
class InterviewFlowPolicyTest {

    private static final int THRESHOLD = 70;
    private static final int LIMIT = 2;
    private static final int QUOTA = 5;

    @Test
    void neverFollowsUpOnTheOpeningQuestion() {
        // 开场题没有考察点，0 分也不能追
        assertThat(decide(true, 0, 0, 0, QUOTA)).isEqualTo(Step.NEXT_MAIN);
    }

    @Test
    void followsUpWhenTheAnswerIsShallowAndDepthAllows() {
        assertThat(decide(false, 40, 0, 0, QUOTA)).isEqualTo(Step.FOLLOW_UP);
    }

    @Test
    void followsUpEvenIfTheMainQuotaIsAlreadyMet() {
        // 追问不计入主问题配额：最后一题答得浅，仍然先追完再收尾
        assertThat(decide(false, 40, 0, QUOTA, QUOTA)).isEqualTo(Step.FOLLOW_UP);
    }

    @Test
    void stopsFollowingUpAtTheDepthLimit() {
        assertThat(decide(false, 40, LIMIT, 0, QUOTA)).isEqualTo(Step.NEXT_MAIN);
    }

    @Test
    void doesNotFollowUpWhenTheAnswerReachesTheThreshold() {
        assertThat(decide(false, THRESHOLD, 0, 0, QUOTA)).isEqualTo(Step.NEXT_MAIN);
    }

    @Test
    void finishesWhenTheQuotaIsMet() {
        assertThat(decide(false, 90, LIMIT, QUOTA, QUOTA)).isEqualTo(Step.FINISH);
    }

    @Test
    void asksAnotherMainQuestionBeforeTheQuotaIsMet() {
        assertThat(decide(false, 90, LIMIT, QUOTA - 1, QUOTA)).isEqualTo(Step.NEXT_MAIN);
    }

    private static Step decide(boolean opening, int score, int depth, int answeredMains, int quota) {
        return InterviewFlowPolicy.decide(opening, score, THRESHOLD, depth, LIMIT, answeredMains, quota);
    }
}
