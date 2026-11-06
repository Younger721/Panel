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
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 面试编排：唯一知道"下一步该谁动"的地方。
 *
 * <pre>
 * create  → Planner(从批注里选题) → Interviewer(第 1 题)
 * answer  → Evaluator(评分+证据) → [Reviewer 仅矛盾时]
 *         → 答得浅且未达追问上限 ? 同点追问 : 下一个点 / 报告
 * </pre>
 *
 * <p>轮次可预期：主问题数 N、每个主问题最多追问 M 次，上限 N×(1+M)。
 * 追问是动态的——答得好就不追问，所以实际轮次通常小于上限。</p>
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private static final int MIN_QUESTIONS = 1;
    private static final int MAX_QUESTIONS = 15;
    private static final int MAX_FOLLOW_UP = 3;

    private final InterviewSessionRepository sessions;
    private final InterviewTurnRepository turns;
    private final ResumeAnalysisService analysisService;
    private final PlannerAgent planner;
    private final InterviewerAgent interviewer;
    private final EvaluatorAgent evaluator;
    private final ReviewerAgent reviewer;
    private final ReportAgent reporter;
    private final ObjectMapper objectMapper;

    /** 低于这个分数才追问；答得好直接进入下一个点。 */
    private final int followUpThreshold;

    public InterviewService(InterviewSessionRepository sessions,
                            InterviewTurnRepository turns,
                            ResumeAnalysisService analysisService,
                            PlannerAgent planner,
                            InterviewerAgent interviewer,
                            EvaluatorAgent evaluator,
                            ReviewerAgent reviewer,
                            ReportAgent reporter,
                            ObjectMapper objectMapper,
                            @Value("${nova.interview.follow-up-threshold:70}") int followUpThreshold) {
        this.sessions = sessions;
        this.turns = turns;
        this.analysisService = analysisService;
        this.planner = planner;
        this.interviewer = interviewer;
        this.evaluator = evaluator;
        this.reviewer = reviewer;
        this.reporter = reporter;
        this.objectMapper = objectMapper;
        this.followUpThreshold = followUpThreshold;
    }

    @Transactional
    public SessionView create(Long resumeId, Integer requestedQuestions, Integer requestedFollowUps) {
        return create(resumeId, requestedQuestions, requestedFollowUps, InterviewProgressListener.NOOP);
    }

    @Transactional
    public SessionView create(Long resumeId, Integer requestedQuestions, Integer requestedFollowUps,
                              InterviewProgressListener listener) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int questionCount = clampQuestions(requestedQuestions);
        int followUpLimit = clampFollowUps(requestedFollowUps);

        long planStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Planner", "从简历批注里选题"));
        ResumeAnalysisService.AnalysisView analysis = analysisService.view(resumeId);
        InterviewPlan plan = planner.plan(analysis, questionCount);
        listener.onEvent("agent", AgentEvent.finished("Planner", "选题",
                plan.points().size() + " 个考察点", plan.points().size(), planStart));

        InterviewSession session = sessions.save(
                InterviewSession.create(resumeId, plan.points().size(), followUpLimit, write(plan)));
        listener.onEvent("session", detail(session.getId()));

        long askStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "出第 1 题"));
        QuestionDraft first = interviewer.next(plan, List.of());
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "出题", "第 1 题", 1, askStart));

        InterviewPlan.Point point = plan.point(first.pointId());
        InterviewTurn turn = turns.save(InterviewTurn.ask(session.getId(), 1, first.pointId(),
                point == null ? "简历" : point.skill(), first.difficulty(), first.question(), first.intent(),
                write(first.followUps()), "MAIN", null, 0));
        session.markInProgress(1);
        sessions.save(session);
        listener.onEvent("question", toQuestion(turn));
        log.info("Interview created: id={}, resumeId={}, questions={}, followUpLimit={}",
                session.getId(), resumeId, plan.points().size(), followUpLimit);
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

        List<InterviewTurn> all = turns.findBySessionId(sessionId);
        InterviewPlan plan = readPlan(session.getPlanJson());
        int answeredMains = (int) all.stream()
                .filter(t -> "MAIN".equals(t.getKind()) && t.getAnswer() != null)
                .count();

        boolean mayFollowUp = finalScore < followUpThreshold
                && turn.getDepth() < session.getFollowUpLimit();
        if (mayFollowUp) {
            return askFollowUp(session, plan, turn, all, listener);
        }
        if (answeredMains >= session.getQuestionCount()) {
            return finishSession(session, all, turn, listener);
        }
        return askNextMain(session, plan, turn, all, listener);
    }

    private AnswerResult askFollowUp(InterviewSession session, InterviewPlan plan, InterviewTurn last,
                                     List<InterviewTurn> all, InterviewProgressListener listener) {
        int rootSeq = "MAIN".equals(last.getKind()) ? last.getSeq()
                : (last.getParentSeq() == null ? last.getSeq() : last.getParentSeq());
        List<InterviewTurn> chain = all.stream()
                .filter(t -> Objects.equals(t.getPointId(), last.getPointId()))
                .toList();
        long start = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "就同一考察点追问"));
        QuestionDraft draft = interviewer.followUp(plan.point(last.getPointId()), chain, last.getAnswer());
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "追问",
                "第 " + (last.getSeq() + 1) + " 题", 1, start));

        InterviewTurn next = turns.save(InterviewTurn.ask(session.getId(), last.getSeq() + 1,
                last.getPointId(), last.getSkill(), last.getDifficulty(), draft.question(), draft.intent(),
                "[]", "FOLLOW_UP", rootSeq, last.getDepth() + 1));
        session.markInProgress(next.getSeq());
        sessions.save(session);
        listener.onEvent("question", toQuestion(next));
        log.info("Follow-up asked: session={}, seq={}, depth={}, point={}",
                session.getId(), next.getSeq(), next.getDepth(), next.getPointId());
        return new AnswerResult(toView(last), toQuestion(next), session.getStatus(),
                session.getOverallScore(), null);
    }

    private AnswerResult askNextMain(InterviewSession session, InterviewPlan plan, InterviewTurn last,
                                     List<InterviewTurn> all, InterviewProgressListener listener) {
        long start = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "出下一题"));
        QuestionDraft draft = interviewer.next(plan, all);
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "出题",
                "第 " + (last.getSeq() + 1) + " 题", 1, start));

        InterviewPlan.Point point = plan.point(draft.pointId());
        InterviewTurn next = turns.save(InterviewTurn.ask(session.getId(), last.getSeq() + 1, draft.pointId(),
                point == null ? last.getSkill() : point.skill(), draft.difficulty(), draft.question(),
                draft.intent(), write(draft.followUps()), "MAIN", null, 0));
        session.markInProgress(next.getSeq());
        sessions.save(session);
        listener.onEvent("question", toQuestion(next));
        return new AnswerResult(toView(last), toQuestion(next), session.getStatus(),
                session.getOverallScore(), null);
    }

    private AnswerResult finishSession(InterviewSession session, List<InterviewTurn> all, InterviewTurn last,
                                       InterviewProgressListener listener) {
        long start = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Report", "生成本场报告"));
        String reportJson = finish(session, all);
        listener.onEvent("agent", AgentEvent.finished("Report", "报告", "已生成", 1, start));
        listener.onEvent("report", read(reportJson));
        return new AnswerResult(toView(last), null, session.getStatus(), session.getOverallScore(),
                read(reportJson));
    }

    public SessionView detail(Long sessionId) {
        InterviewSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<TurnView> views = turns.findBySessionId(sessionId).stream().map(this::toView).toList();
        return new SessionView(session.getId(), session.getResumeId(), session.getStatus(),
                session.getTurnBudget(), session.getQuestionCount(), session.getFollowUpLimit(),
                session.getCurrentTurn(), session.getOverallScore(), session.getCreatedAt(), views);
    }

    /** 历史记录：按创建时间倒序，带一句报告摘要便于列表展示。 */
    public List<SessionSummary> list() {
        return sessions.findAll().stream().map(session -> new SessionSummary(
                session.getId(), session.getResumeId(), session.getStatus(),
                session.getQuestionCount(), session.getFollowUpLimit(),
                session.getOverallScore(), summaryOf(session.getReportJson()),
                session.getCreatedAt())).toList();
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
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0));
        String reportJson = write(new ReportPayload(data, overall));
        session.markCompleted(reportJson, overall);
        sessions.save(session);
        return reportJson;
    }

    private String summaryOf(String reportJson) {
        JsonNode node = read(reportJson);
        if (node == null) {
            return null;
        }
        String summary = node.path("report").path("summary").asText("");
        if (summary.isBlank()) {
            return null;
        }
        return summary.length() <= 120 ? summary : summary.substring(0, 120) + "…";
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

    private static int clampQuestions(Integer requested) {
        int value = requested == null ? 5 : requested;
        return Math.max(MIN_QUESTIONS, Math.min(MAX_QUESTIONS, value));
    }

    private static int clampFollowUps(Integer requested) {
        int value = requested == null ? 2 : requested;
        return Math.max(0, Math.min(MAX_FOLLOW_UP, value));
    }

    private TurnView toView(InterviewTurn turn) {
        return new TurnView(turn.getSeq(), turn.getKind(), turn.getParentSeq(), turn.getDepth(),
                turn.getQuestion(), turn.getSkill(), turn.getDifficulty(), turn.getAnswer(),
                turn.getFinalScore(), turn.getFeedback(), read(turn.getScoresJson()),
                read(turn.getReviewJson()), parseFollowUps(turn.getFollowUps()));
    }

    private QuestionView toQuestion(InterviewTurn turn) {
        return new QuestionView(turn.getSeq(), turn.getKind(), turn.getParentSeq(), turn.getDepth(),
                turn.getQuestion(), turn.getSkill(), turn.getDifficulty(), parseFollowUps(turn.getFollowUps()));
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

    public record ReportPayload(InterviewReportData report, int overallScore) {
    }

    public record SessionView(Long id, Long resumeId, InterviewStatus status, int turnBudget,
                              int questionCount, int followUpLimit, int currentTurn, Integer overallScore,
                              java.time.Instant createdAt, List<TurnView> turns) {
    }

    public record SessionSummary(Long id, Long resumeId, InterviewStatus status, int questionCount,
                                 int followUpLimit, Integer overallScore, String summary,
                                 java.time.Instant createdAt) {
    }

    public record TurnView(int seq, String kind, Integer parentSeq, int depth, String question, String skill,
                           String difficulty, String answer, Integer score, String feedback,
                           JsonNode evaluation, JsonNode review, List<String> followUps) {
    }

    public record QuestionView(int seq, String kind, Integer parentSeq, int depth, String question,
                               String skill, String difficulty, List<String> followUps) {
    }

    public record AnswerResult(TurnView evaluatedTurn, QuestionView nextQuestion, InterviewStatus status,
                               Integer overallScore, JsonNode report) {
    }
}
