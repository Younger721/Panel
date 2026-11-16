package com.panel.interview.application;

/**
 * "答完这一题之后下一步该谁动"的判定：把 {@code InterviewService.answer()} 里的
 * if/else 抽成一个纯函数。
 *
 * <p>抽出来唯一但足够重要的理由是<b>可测</b>：这段规则决定面试是追问、换题还是收尾，
 * 原来埋在事务方法里，分支对错只能靠跑一场真面试来验证。现在它不依赖 session、
 * 不依赖数据库，七个分支可以逐个钉死。</p>
 */
public final class InterviewFlowPolicy {

    /** 一次作答之后的下一步。 */
    public enum Step {
        /** 就同一个考察点再追一层。 */
        FOLLOW_UP,
        /** 换下一个考察点。 */
        NEXT_MAIN,
        /** 主问题已经问够，生成报告收尾。 */
        FINISH
    }

    private InterviewFlowPolicy() {
    }

    /**
     * @param openingTurn       刚答完的是不是开场自我介绍
     * @param finalScore        本题最终得分（复核之后）
     * @param followUpThreshold 人设的追问阈值：低于它才值得追
     * @param depth             当前追问层级，主问题为 0
     * @param followUpLimit     单个考察点的追问上限
     * @param answeredMains     已作答的主问题数（含开场题）
     * @param questionCount     本场主问题配额
     */
    public static Step decide(boolean openingTurn, int finalScore, int followUpThreshold,
                              int depth, int followUpLimit, int answeredMains, int questionCount) {
        // 开场题没有考察点，追问会跑题
        boolean mayFollowUp = !openingTurn
                && finalScore < followUpThreshold
                && depth < followUpLimit;
        if (mayFollowUp) {
            // 追问不计入主问题配额，所以即使主问题已经问够，也可以先把这一层追完
            return Step.FOLLOW_UP;
        }
        if (answeredMains >= questionCount) {
            return Step.FINISH;
        }
        return Step.NEXT_MAIN;
    }
}
