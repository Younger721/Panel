package com.nova.interview.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一次问答。题目、作答、评估、复核都挂在这一行上。
 *
 * <p>把评估结果放在 turn 上而不是单独的表：一轮面试的分数、证据、复核理由
 * 天然是同一生命周期，拆表只会多一次 join。JSON 存 {@code scores_json} /
 * {@code review_json}，结构由 {@code TurnEvaluation} / {@code ReviewDecision} 定义。</p>
 */
@Entity
@Table(name = "interview_turn",
        uniqueConstraints = @UniqueConstraint(name = "uk_session_seq", columnNames = {"session_id", "seq"}))
public class InterviewTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private int seq;

    /** 对应考察点矩阵里的 point id，用于保证"每个点只问一次"。 */
    @Column(length = 32)
    private String pointId;

    @Column(length = 64)
    private String skill;

    @Column(length = 16)
    private String difficulty;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String question;

    @Column(length = 512)
    private String intent;

    /** 追问列表（JSON 数组字符串），仅作展示，不参与编排。 */
    @Column(length = 1000)
    private String followUps;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String answer;

    /** 序列化后的评估结果（含证据与扣分理由）。 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String scoresJson;

    /** Reviewer 的仲裁结果；为空表示未触发复核。 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String reviewJson;

    private Integer finalScore;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String feedback;

    @Column(nullable = false)
    private Instant askedAt = Instant.now();

    private Instant answeredAt;

    protected InterviewTurn() {
    }

    public static InterviewTurn ask(Long sessionId, int seq, String pointId, String skill, String difficulty,
                                    String question, String intent, String followUps) {
        InterviewTurn turn = new InterviewTurn();
        turn.sessionId = sessionId;
        turn.seq = seq;
        turn.pointId = pointId;
        turn.skill = skill;
        turn.difficulty = difficulty;
        turn.question = question;
        turn.intent = intent;
        turn.followUps = followUps;
        return turn;
    }

    public void answer(String answerText) {
        this.answer = answerText;
        this.answeredAt = Instant.now();
    }

    public void evaluated(String scoresJson, Integer finalScore, String feedback, String reviewJson) {
        this.scoresJson = scoresJson;
        this.finalScore = finalScore;
        this.feedback = feedback;
        this.reviewJson = reviewJson;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public int getSeq() {
        return seq;
    }

    public String getPointId() {
        return pointId;
    }

    public String getSkill() {
        return skill;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getQuestion() {
        return question;
    }

    public String getIntent() {
        return intent;
    }

    public String getFollowUps() {
        return followUps;
    }

    public String getAnswer() {
        return answer;
    }

    public String getScoresJson() {
        return scoresJson;
    }

    public String getReviewJson() {
        return reviewJson;
    }

    public Integer getFinalScore() {
        return finalScore;
    }

    public String getFeedback() {
        return feedback;
    }

    public Instant getAskedAt() {
        return askedAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }
}
