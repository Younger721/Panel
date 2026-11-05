package com.nova.interview.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.interview.agent.EvaluatorAgent;
import com.nova.interview.agent.InterviewerAgent;
import com.nova.interview.agent.PlannerAgent;
import com.nova.interview.agent.ReportAgent;
import com.nova.interview.agent.ReviewerAgent;
import com.nova.interview.agent.model.InterviewPlan;
import com.nova.interview.agent.model.InterviewReportData;
import com.nova.interview.agent.model.QuestionDraft;
import com.nova.interview.agent.model.ReviewDecision;
import com.nova.interview.agent.model.TurnEvaluation;
import com.nova.interview.domain.model.InterviewSession;
import com.nova.interview.domain.model.InterviewStatus;
import com.nova.interview.domain.model.InterviewTurn;
import com.nova.interview.domain.repository.InterviewSessionRepository;
import com.nova.interview.domain.repository.InterviewTurnRepository;
import com.nova.interview.domain.repository.ResumeProfileRepository;
import com.nova.interview.domain.repository.ResumeRepository;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试编排：唯一知道"下一步该谁动"的地方。
 *
 * <p>流程（每个 Agent 只做一件事，状态全部落库）：</p>
 * <pre>
 * create  → Planner(考察点矩阵) → Interviewer(第1题)        → IN_PROGRESS
 * answer  → Evaluator(带证据打分) → [Reviewer 仅在有矛盾时]  → 下一题 / 报告
 * </pre>
 *
 * <p>两条硬规则写在代码里而不是提示词里：</p>
 * <ol>
 *   <li>题目必须落在未覆盖的考察点上（{@link InterviewerAgent} 校验）；</li>
 *   <li>总分由逐题分数重算，不采信模型自报的总分。</li>
 * </ol>
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private static final int MIN_TURNS = 3;
    private static final int MAX_TURNS = 10;

    private final InterviewSessionRepository sessions;
    private final InterviewTurnRepository turns;
    private final ResumeRepository resumes;
    private final ResumeProfileRepository profiles;
    private final PlannerAgent planner;
    private final InterviewerAgent interviewer;
    private final EvaluatorAgent evaluator;
    private final ReviewerAgent reviewer;
    private final ReportAgent reporter;
    private final ObjectMapper objectMapper;

    public InterviewService(InterviewSessionRepository sessions,
                            InterviewTurnRepository turns,
                            ResumeRepository resumes,
                            ResumeProfileRepository profiles,
                            PlannerAgent planner,
                            InterviewerAgent interviewer,
                            EvaluatorAgent evaluator,
                            ReviewerAgent reviewer,
                            ReportAgent reporter,
                            ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.turns = turns;
        this.resumes = resumes;
        this.profiles = profiles;
        this.planner = planner;
        this.interviewer = interviewer;
        this.evaluator = evaluator;
        this.reviewer = reviewer;
        this.reporter = reporter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SessionView create(Long resumeId, Integer requestedTurns) {
        return create(resumeId, requestedTurns, InterviewProgressListener.NOOP);
    }

    @Transactional
    public SessionView create(Long resumeId, Integer requestedTurns, InterviewProgressListener listener) {
        int budget = clampTurns(requestedTurns);
        ParsedResume resume = loadResume(resumeId);

        long planStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Planner", "生成考察点矩阵"));
        InterviewPlan plan = planner.plan(resume, budget);
        listener.onEvent("agent", AgentEvent.finished("Planner", "考察点矩阵",
                plan.points().size() + " 个考察点", plan.points().size(), planStart));

        InterviewSession session = sessions.save(InterviewSession.create(resumeId, budget, write(plan)));
        listener.onEvent("session", detail(session.getId()));

        long askStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "出第 1 题"));
        QuestionDraft first = interviewer.next(plan, List.of());
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "出题", "第 1 题", 1, askStart));

        InterviewTurn turn = turns.save(InterviewTurn.ask(session.getId(), 1, first.pointId(),
                plan.point(first.pointId()).skill(), first.difficulty(), first.question(), first.intent(),
                write(first.followUps())));
        session.markInProgress(1);
        sessions.save(session);
        listener.onEvent("question", toQuestion(turn));
        log.info("Interview created: id={}, resumeId={}, turns={}, firstPoint={}",
                session.getId(), resumeId, budget, first.pointId());
        return detail(session.getId());
    }

    @Transactional
    public AnswerResult answer(Long sessionId, String answerText) {
        return answer(sessionId, answerText, InterviewProgressListener.NOOP);
    }

    @Transactional
    public AnswerResult answer(Long sessionId, String answerText, InterviewProgressListener listener) {
        if (answerText == null || answerText.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        InterviewTurn turn = turns.findPending(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        turn.answer(answerText.trim());

        long evalStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Evaluator", "评分并提取证据"));
        TurnEvaluation evaluation = evaluator.evaluate(turn.getQuestion(), turn.getSkill(), turn.getAnswer());
        listener.onEvent("agent", AgentEvent.finished("Evaluator", "评分",
                "第 " + turn.getSeq() + " 题", 1, evalStart));

        ReviewDecision review = null;
        if (suspicious(evaluation, answerText)) {
            long reviewStart = System.currentTimeMillis();
            listener.onEvent("agent", AgentEvent.started("Reviewer", "复核评分与证据是否矛盾"));
            review = reviewer.review(turn.getQuestion(), turn.getAnswer(), evaluation);
            listener.onEvent("agent", AgentEvent.finished("Reviewer", "复核",
                    review.adjusted() ? "已调整" : "维持原分", review.adjustments().size(), reviewStart));
        }
        int finalScore = applyReview(evaluation, review);
        turn.evaluated(write(evaluation), finalScore, feedbackOf(evaluation, review),
                review == null ? null : write(review));
        turns.save(turn);
        listener.onEvent("evaluation", toView(turn));

        if (turn.getSeq() >= session.getTurnBudget()) {
            long reportStart = System.currentTimeMillis();
            listener.onEvent("agent", AgentEvent.started("Report", "生成本场报告"));
            String reportJson = finish(session, turns.findBySessionId(sessionId));
            listener.onEvent("agent", AgentEvent.finished("Report", "报告", "已生成", 1, reportStart));
            listener.onEvent("report", read(reportJson));
            return new AnswerResult(toView(turn), null, session.getStatus(), session.getOverallScore(),
                    read(reportJson));
        }

        InterviewPlan plan = readPlan(session.getPlanJson());
        List<InterviewTurn> answered = turns.findBySessionId(sessionId);
        long askStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "出下一题"));
        QuestionDraft next = interviewer.next(plan, answered);
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "出题",
                "第 " + (turn.getSeq() + 1) + " 题", 1, askStart));
        InterviewTurn nextTurn = turns.save(InterviewTurn.ask(sessionId, turn.getSeq() + 1, next.pointId(),
                plan.point(next.pointId()).skill(), next.difficulty(), next.question(), next.intent(),
                write(next.followUps())));
        session.markInProgress(nextTurn.getSeq());
        sessions.save(session);
        listener.onEvent("question", toQuestion(nextTurn));
        return new AnswerResult(toView(turn), toQuestion(nextTurn), session.getStatus(),
                session.getOverallScore(), null);
    }

    public SessionView detail(Long sessionId) {
        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<TurnView> views = turns.findBySessionId(sessionId).stream().map(this::toView).toList();
        return new SessionView(session.getId(), session.getResumeId(), session.getStatus(),
                session.getTurnBudget(), session.getCurrentTurn(), session.getOverallScore(),
                session.getCreatedAt(), views);
    }

    public JsonNode report(Long sessionId) {
        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (session.getReportJson() == null) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return read(session.getReportJson());
    }

    /** 收尾：写报告 + 用逐题分数重算总分（覆盖模型自报总分）。 */
    private String finish(InterviewSession session, List<InterviewTurn> answered) {
        InterviewReportData data = reporter.report(answered);
        int overall = (int) Math.round(answered.stream()
                .map(InterviewTurn::getFinalScore)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0));
        String reportJson = write(new ReportPayload(data, overall));
        session.markCompleted(reportJson, overall);
        sessions.save(session);
        return reportJson;
    }

    /** 三种"评分可疑"的情况才复核，正常回答不额外烧一次模型调用。 */
    private static boolean suspicious(TurnEvaluation evaluation, String answer) {
        if (!evaluation.hasEvidence()) {
            return true;
        }
        if (!evaluation.isAnswered() && answer.trim().length() > 40) {
            return true;
        }
        return evaluation.scores().weightedTotal() == 0 && answer.trim().length() > 40;
    }

    private int applyReview(TurnEvaluation evaluation, ReviewDecision review) {
        if (review == null || !review.adjusted() || review.adjustments().isEmpty()) {
            return evaluation.scores().weightedTotal();
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("accuracy", evaluation.scores().accuracy());
        scores.put("depth", evaluation.scores().depth());
        scores.put("completeness", evaluation.scores().completeness());
        scores.put("clarity", evaluation.scores().clarity());
        for (ReviewDecision.Adjustment adjustment : review.adjustments()) {
            if (adjustment.axis() != null && scores.containsKey(adjustment.axis())) {
                scores.put(adjustment.axis(), Math.max(0, Math.min(100, adjustment.to())));
            }
        }
        return (int) Math.round(scores.get("accuracy") * 0.40 + scores.get("depth") * 0.25
                + scores.get("completeness") * 0.20 + scores.get("clarity") * 0.15);
    }

    private static String feedbackOf(TurnEvaluation evaluation, ReviewDecision review) {
        String feedback = evaluation.feedback() == null ? "" : evaluation.feedback();
        if (review != null && review.adjusted() && review.reason() != null) {
            feedback = feedback + "\n复核意见：" + review.reason();
        }
        return feedback;
    }

    private ParsedResume loadResume(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        resumes.findById(resumeId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var profile = profiles.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        try {
            return objectMapper.readValue(profile.getPayloadJson(), ParsedResume.class);
        } catch (Exception ex) {
            throw new IllegalStateException("stored resume profile is not readable", ex);
        }
    }

    private static int clampTurns(Integer requested) {
        int turns = requested == null ? 5 : requested;
        return Math.max(MIN_TURNS, Math.min(MAX_TURNS, turns));
    }

    private TurnView toView(InterviewTurn turn) {
        return new TurnView(turn.getSeq(), turn.getQuestion(), turn.getSkill(), turn.getDifficulty(),
                turn.getAnswer(), turn.getFinalScore(), turn.getFeedback(),
                read(turn.getScoresJson()), read(turn.getReviewJson()), parseFollowUps(turn.getFollowUps()));
    }

    private QuestionView toQuestion(InterviewTurn turn) {
        return new QuestionView(turn.getSeq(), turn.getQuestion(), turn.getSkill(), turn.getDifficulty(),
                parseFollowUps(turn.getFollowUps()));
    }

    private List<String> parseFollowUps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private InterviewPlan readPlan(String json) {
        try {
            return objectMapper.readValue(json, InterviewPlan.class);
        } catch (Exception ex) {
            throw new IllegalStateException("stored interview plan is not readable", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize agent output", ex);
        }
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 报告落库结构：模型写的正文 + 代码算的总分。 */
    public record ReportPayload(InterviewReportData report, int overallScore) {
    }

    public record SessionView(Long id, Long resumeId, InterviewStatus status, int turnBudget, int currentTurn,
                              Integer overallScore, java.time.Instant createdAt, List<TurnView> turns) {
    }

    public record TurnView(int seq, String question, String skill, String difficulty, String answer,
                           Integer score, String feedback, JsonNode evaluation, JsonNode review,
                           List<String> followUps) {
    }

    public record QuestionView(int seq, String question, String skill, String difficulty,
                               List<String> followUps) {
    }

    public record AnswerResult(TurnView evaluatedTurn, QuestionView nextQuestion, InterviewStatus status,
                               Integer overallScore, JsonNode report) {
    }
}
