package com.panel.knowledge.application;

import java.time.Instant;
import java.util.List;

/** 刷题相关的返回体，集中放一处，避免十几个只有 3 个字段的 record 散落成文件。 */
public final class KbQuizViews {

    private KbQuizViews() {
    }

    /** 题面：只有题干和元信息，**不含答案**；答案要另外调 reveal。 */
    public record Item(Long attemptId, int ordinal, Long questionId, String stem, String difficulty,
                       List<String> tags, boolean answered, String result) {
    }

    /** 一次会话：刷新后靠它续上进度。 */
    public record Session(Long sessionId, Long kbId, String status, int questionCount, int answeredCount,
                          int correctCount, int wrongCount, Instant startedAt, Instant finishedAt,
                          List<Item> items) {
    }

    /** 知识点：目录节点 + 前端可直接跳的深链。 */
    public record Point(Long categoryId, String name, String path, String catalogLink, String description) {
    }

    /** 相邻知识点（横切关系）。direction 为 OUT 时当前节点是起点。 */
    public record Related(Long categoryId, String name, String relation, double weight, String direction) {
    }

    public record Reveal(Long attemptId, Long questionId, String stem, String answer, long thinkingSeconds,
                         Point point, List<Related> related) {
    }

    /** 自评后的即时反馈：答错就把知识点和相邻节点一起推出来。 */
    public record Grade(Long attemptId, String result, boolean correct, int answeredCount, int questionCount,
                        boolean finished, Summary summary, Point point, List<Related> related) {
    }

    public record Summary(int correctCount, int wrongCount, double accuracy, List<WrongItem> wrongItems) {
    }

    public record WrongItem(Long questionId, String stem, Long categoryId, String categoryPath, long wrongCount) {
    }

    /** 掌握度：整库视角，前端顶部一条就够。 */
    public record Stats(Long kbId, long totalQuestions, long assignedQuestions, long attemptedQuestions,
                        long answered, long correct, long wrong, long unsure, double accuracy,
                        long wrongDistinctQuestions, List<WeakPoint> weakPoints,
                        List<Session> recentSessions) {
    }

    public record WeakPoint(Long categoryId, String name, String path, long wrongCount) {
    }

    /**
     * 一个知识点的能力值视图。
     *
     * <p>{@code theta} 是原始分，{@code thetaEffective} 是扣掉遗忘衰减后的分数；
     * {@code wrongRate} 是"下次抽到中等难度题，你答错的概率估计"，给人看的那个数字。</p>
     */
    public record SkillPoint(Long categoryId, String name, String path, int depth, boolean leaf,
                             int attempts, int correct, int wrong, int unsure, int interviewCount,
                             double theta, double thetaEffective, double wrongRate,
                             String lastResult, Instant lastSeenAt) {
    }

    /** 能力值总览：叶子节点按弱到强排，父节点是按题量加权聚合出来的"整块弱"。 */
    public record Skills(Long kbId, int trackedPoints, List<SkillPoint> points, List<SkillPoint> branches) {
    }

    /** 面试信号回填结果：{@code dryRun} 时 {@code applied} 恒为 0，只看"会匹配到什么"。 */
    public record InterviewBackfill(Long kbId, Long userId, boolean dryRun, int considered, int matched,
                                    int applied, int unmatchedSkills, int createdCategories,
                                    List<String> unmatchedSamples, List<InterviewCategory> byCategory,
                                    long importedTotal) {
    }

    /** 回填按知识点汇总：哪个知识点吃到了几轮面试。 */
    public record InterviewCategory(Long categoryId, String name, int turns) {
    }

    /** 目录覆盖率：错题联动能不能用，取决于这个数字。 */
    public record Coverage(Long kbId, long totalQuestions, long assignedQuestions, long uncategorizedQuestions,
                           long lowConfidenceQuestions, long categoryCount, long leafCategoryCount,
                           double coverageRatio, List<Gap> gaps, List<DocumentGap> documents) {
    }

    public record Gap(Long questionId, String stem, Long categoryId, String categoryName, Double confidence) {
    }

    /** 按文档看的缺口：哪份文档导进来之后题目还没挂上知识点。 */
    public record DocumentGap(Long documentId, String documentName, long total, long assigned,
                              long lowConfidence) {
        public long uncategorized() {
            return Math.max(0, total - assigned);
        }
    }

    /** 知识点关系（管理用）。 */
    public record Relation(Long id, Long kbId, Long fromCategoryId, String fromName, Long toCategoryId,
                           String toName, String relation, double weight, String note, Instant createdAt) {
    }
}
