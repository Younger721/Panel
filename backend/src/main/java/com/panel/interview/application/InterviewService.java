package com.panel.interview.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.agent.EvaluatorAgent;
import com.panel.interview.agent.ExpressionJudgeAgent;
import com.panel.interview.agent.InterviewerAgent;
import com.panel.interview.agent.JobFitAgent;
import com.panel.interview.agent.PlannerAgent;
import com.panel.interview.agent.ReportAgent;
import com.panel.interview.agent.ReviewerAgent;
import com.panel.interview.agent.model.InterviewPlan;
import com.panel.interview.agent.model.InterviewReportData;
import com.panel.interview.agent.model.JobFit;
import com.panel.interview.agent.model.QuestionDraft;
import com.panel.interview.agent.model.ReviewDecision;
import com.panel.interview.agent.model.TurnEvaluation;
import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewStatus;
import com.panel.interview.domain.model.InterviewTurn;
import com.panel.interview.domain.model.InterviewerPersona;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.model.ResumeStatus;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import com.panel.interview.domain.repository.InterviewTurnRepository;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import com.panel.shared.security.SecurityContextPropagation;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 面试编排：唯一知道"下一步该谁动"的地方。
 *
 * <pre>
 * create  → Planner(从批注里选题) → Interviewer(第 1 题)
 * answer  → 技术评委 ∥ 表达评委（并行） → [Reviewer 仅矛盾时]
 *         → 答得浅且未达追问上限 ? 同点追问 : 下一个点 / 报告
 * </pre>
 *
 * <p>轮次可预期：主问题数 N、每个主问题最多追问 M 次，上限 N×(1+M)。
 * 追问是动态的——答得好就不追问，所以实际轮次通常小于上限。</p>
 *
 * <h2>事务边界</h2>
 * <p>这个方法里每次模型调用都要 3~30 秒。<b>事务只包住读写库的那几行</b>，模型调用
 * 全部在事务外跑：否则十几个并发就能把 Hikari 的 10 个连接占满，登录、列表一起超时。
 * 落库被拆成若干个 {@link TransactionTemplate} 短事务。</p>
 *
 * <h2>作答幂等</h2>
 * <p>同一题用 {@code UPDATE ... WHERE answer IS NULL} 做原子认领：双击提交、前端重试、
 * 两个标签页同时答，只有一个能拿到这一题。拿不到的直接 409，不会出现"同一题评两次分、
 * 出了两道下一题、后插者撞唯一键 500"。</p>
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private static final int MIN_QUESTIONS = 1;
    private static final int MAX_QUESTIONS = 15;
    private static final int MAX_FOLLOW_UP = 3;
    private static final int MAX_JD_CHARS = 20_000;
    /**
     * 单题回答长度上限。
     *
     * <p>回答会原样进入评分与出题两次模型调用，不设上限时一次误粘贴（整篇文档）
     * 就能把上下文顶穿，用户拿到的是 500 而不是"这条太长了"。4000 字已经是
     * 一段十几分钟口述的量，正常语音转写远达不到。</p>
     */
    private static final int MAX_ANSWER_CHARS = 4_000;

    /** 第 1 题固定是自我介绍：真实面试都这么开场，也给后面的追问一个入口。 */
    private static final String OPENING_QUESTION =
            "先做个自我介绍吧，重点说说你最近做过的项目，以及其中哪一部分是你自己负责的。";

    /** 开场题的 kind。不算 MAIN（不占考察点），也不算 FOLLOW_UP（不参与追问）。 */
    private static final String KIND_OPENING = "OPENING";
    private static final String KIND_FOLLOW_UP = "FOLLOW_UP";

    private final InterviewSessionRepository sessions;
    private final InterviewTurnRepository turns;
    private final ResumeAnalysisService analysisService;
    private final ResumeAccessGuard resumeAccess;
    private final CurrentActorProvider actors;
    private final PlannerAgent planner;
    private final InterviewerAgent interviewer;
    private final EvaluatorAgent evaluator;
    private final ExpressionJudgeAgent expressionJudge;
    private final ReviewerAgent reviewer;
    private final ReportAgent reporter;
    private final JobFitAgent jobFit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    /** 两个评委并行打分用的线程池：整个应用共用一个，别每次请求都新建。 */
    private final ExecutorService judgeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public InterviewService(InterviewSessionRepository sessions,
                            InterviewTurnRepository turns,
                            ResumeAnalysisService analysisService,
                            ResumeAccessGuard resumeAccess,
                            CurrentActorProvider actors,
                            PlannerAgent planner,
                            InterviewerAgent interviewer,
                            EvaluatorAgent evaluator,
                            ExpressionJudgeAgent expressionJudge,
                            ReviewerAgent reviewer,
                            ReportAgent reporter,
                            JobFitAgent jobFit,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.turns = turns;
        this.analysisService = analysisService;
        this.resumeAccess = resumeAccess;
        this.actors = actors;
        this.planner = planner;
        this.interviewer = interviewer;
        this.evaluator = evaluator;
        this.expressionJudge = expressionJudge;
        this.reviewer = reviewer;
        this.reporter = reporter;
        this.jobFit = jobFit;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PreDestroy
    void shutdown() {
        judgeExecutor.shutdownNow();
    }

    public SessionView create(Long resumeId, Integer requestedQuestions, Integer requestedFollowUps) {
        return create(resumeId, requestedQuestions, requestedFollowUps, InterviewerPersona.SERIOUS, null,
                InterviewProgressListener.NOOP);
    }

    public SessionView create(Long resumeId, Integer requestedQuestions, Integer requestedFollowUps,
                              InterviewProgressListener listener) {
        return create(resumeId, requestedQuestions, requestedFollowUps, InterviewerPersona.SERIOUS, null, listener);
    }

    public SessionView create(Long resumeId, Integer requestedQuestions, Integer requestedFollowUps,
                              InterviewerPersona persona, InterviewProgressListener listener) {
        return create(resumeId, requestedQuestions, requestedFollowUps, persona, null, listener);
    }

    /**
     * @param persona 面试官人设；决定本场的追问阈值。为空时按严肃型处理。
     * @param jd      岗位 JD 原文；为空时是纯简历面试，选题规则与之前完全一致。
     */
    public SessionView create(Long resumeId, Integer requestedQuestions, Integer requestedFollowUps,
                              InterviewerPersona persona, String jd, InterviewProgressListener listener) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        // 简历归属先挡住：不能拿别人的简历开一场面试（会话里会存简历摘要、逐题回答）
        Resume resume = resumeAccess.require(resumeId);
        // 没解析完就开面试，Planner 拿到的是空批注（这里原本会以 404 冒出来，
        // 让人以为简历不存在）——明确告诉她"等一会儿"
        if (resume.getStatus() != ResumeStatus.PARSED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "简历还没解析完成（" + resume.getStatus() + "），等解析结束后再开始面试");
        }
        CurrentActor actor = actors.requireCurrent();
        // 连点两下「开始面试」不该变成两场会话：一场 5 次模型调用，另一场注定被丢掉。
        // 复用进行中的那一场，用户看到的就是自己想要的结果。
        Optional<InterviewSession> running = sessions.findLatestInProgress(actor.userId())
                .filter(existing -> Objects.equals(existing.getResumeId(), resumeId));
        if (running.isPresent()) {
            log.info("Interview create reused in-progress session {} for resume {}", running.get().getId(), resumeId);
            return detail(running.get().getId());
        }
        int questionCount = clampQuestions(requestedQuestions);
        int followUpLimit = clampFollowUps(requestedFollowUps);
        String normalizedJd = normalizeJd(jd);

        ResumeAnalysisService.AnalysisView analysis = analysisService.view(resumeId);

        // 以下两次模型调用都在事务外
        JobFit fit = analyzeFit(normalizedJd, analysis, listener);
        long planStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Planner", "从简历批注里选题"));
        // 第 1 题是固定的自我介绍，所以考察点只排 N-1 个，总题数仍是用户设的 N
        InterviewPlan plan = planner.plan(analysis, Math.max(0, questionCount - 1), fit)
                .withJobFit(normalizedJd, fit);
        listener.onEvent("agent", AgentEvent.finished("Planner", "选题",
                plan.points().size() + " 个考察点", plan.points().size(), planStart));

        long askStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "出第 1 题"));
        // 开场白是确定的，不调模型：省一次调用，也保证每场面试都从自我介绍开始
        QuestionDraft first = new QuestionDraft(OPENING_QUESTION, KIND_OPENING, "BASIC", null,
                "让候选人自述经历主线，作为后续提问的入口", List.of());
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "出题", "第 1 题", 1, askStart));

        InterviewSession session;
        try {
            session = tx.execute(status -> {
                InterviewSession created = sessions.save(InterviewSession.create(resumeId, actor.userId(),
                        plan.points().size() + 1, followUpLimit, write(plan), persona));
                InterviewTurn turn = turns.save(InterviewTurn.ask(created.getId(), 1, null, "自我介绍",
                        first.difficulty(), first.question(), first.intent(),
                        write(first.followUps()), KIND_OPENING, null, 0));
                created.markInProgress(1);
                sessions.save(created);
                return created;
            });
        } catch (RuntimeException ex) {
            // 上面的 findLatestInProgress 只是快路径：两个请求同时进来时都还看不到对方的行。
            // 真正的护栏是 uk_session_active（同 owner + 同简历只允许一场 IN_PROGRESS），
            // 撞上它就说明对方刚建好，直接复用那一场。
            Optional<InterviewSession> raced = sessions.findLatestInProgress(actor.userId())
                    .filter(existing -> Objects.equals(existing.getResumeId(), resumeId));
            if (raced.isPresent()) {
                log.info("Interview create lost the race, reusing session {} for resume {}",
                        raced.get().getId(), resumeId);
                return detail(raced.get().getId());
            }
            throw ex;
        }
        if (session == null) {
            throw new IllegalStateException("interview session was not created");
        }
        listener.onEvent("session", detail(session.getId()));
        listener.onEvent("question", toQuestion(turns.findPending(session.getId()).orElseThrow()));
        log.info("Interview created: id={}, resumeId={}, questions={}, followUpLimit={}",
                session.getId(), resumeId, session.getQuestionCount(), followUpLimit);
        return detail(session.getId());
    }

    public AnswerResult answer(Long sessionId, String answerText) {
        return answer(sessionId, answerText, null, InterviewProgressListener.NOOP);
    }

    public AnswerResult answer(Long sessionId, String answerText, InterviewProgressListener listener) {
        return answer(sessionId, answerText, null, listener);
    }

    public AnswerResult answer(Long sessionId, String answerText, Integer expectedSeq) {
        return answer(sessionId, answerText, expectedSeq, InterviewProgressListener.NOOP);
    }

    /**
     * @param expectedSeq 前端看到的题号；不为空时校验，防止"用户重提的是上一题、
     *                    但服务端 pending 已经换成下一题"导致答案错位记到别的题上。
     */
    public AnswerResult answer(Long sessionId, String answerText, Integer expectedSeq,
                               InterviewProgressListener listener) {
        if (answerText == null || answerText.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String text = answerText.trim();
        if (text.length() > MAX_ANSWER_CHARS) {
            // 不截断：用户会以为自己那段"关键细节"已经交上去了，实际被砍掉照样影响评分。
            // 口头回答的长度上限是分钟级的，超过这个数基本是误粘贴了别的东西。
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "回答太长（" + text.length() + " 字），请控制在 " + MAX_ANSWER_CHARS + " 字以内");
        }
        InterviewSession session = requireOpenSession(sessionId);
        InterviewTurn pending = turns.findPending(sessionId)
                // 这里原来只抛裸 CONFLICT，用户看到"资源冲突"四个字完全不知道该做什么。
                // 没有待答题几乎总是"这场面试在别处已经推进完了"。
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "这场面试已经没有等待作答的题目了（可能在另一个窗口已经答完或已结束）。"
                        + "刷新页面看看最新进度。"));
        if (expectedSeq != null && expectedSeq != pending.getSeq()) {
            // 客户端持有的题目已经过期（另一个标签页/重试已经推进过），让它刷新再答
            throw new BusinessException(ErrorCode.CONFLICT,
                    "你手上的题目已经过期了（第 " + expectedSeq + " 题已推进到第 " + pending.getSeq()
                    + " 题）。刷新页面拿到最新题目再答。");
        }

        // 原子认领：并发下只有一个请求能把答案写进这一行
        Integer claimed = tx.execute(status -> turns.claimAnswer(pending.getId(), text, Instant.now()));
        if (claimed == null || claimed == 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "这道题刚刚已经提交过了（可能是重复点击，或另一个窗口先提交了）。"
                    + "刷新页面确认最新进度，不要重复提交。");
        }

        JudgePanel panel;
        ReviewDecision review;
        try {
            panel = judge(pending, text, listener);
            TurnEvaluation evaluation = panel.technical();
            review = suspicious(evaluation, text)
                    ? reviewTurn(pending, text, evaluation, listener)
                    : null;
        } catch (RuntimeException ex) {
            // 模型阶段失败：把认领还回去，用户可以原样重答，不必重开一场
            tx.execute(status -> turns.releaseAnswer(pending.getId(), text));
            log.warn("Answer evaluation failed, claim released: session={}, seq={}, error={}",
                    sessionId, pending.getSeq(), ex.toString());
            throw ex;
        }

        TurnEvaluation evaluation = panel.technical();
        int finalScore = applyReview(evaluation, review);
        String scoresJson = EvaluationJson.write(objectMapper, evaluation, panel.expression());
        String reviewJson = review == null ? null : write(review);
        String feedback = feedbackOf(evaluation, review);
        InterviewTurn evaluated;
        try {
            evaluated = tx.execute(status -> {
                InterviewTurn fresh = turns.findById(pending.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                fresh.evaluated(scoresJson, finalScore, feedback, reviewJson);
                return turns.save(fresh);
            });
        } catch (RuntimeException ex) {
            // 认领已经写进 answer 了，但分数没落库：这一行会变成"有回答无评分"，
            // findPending 再也找不到它，用户重答又是 409——整场面试就此卡死。
            // 归还认领，让她原样再答一次。
            tx.execute(status -> turns.releaseAnswer(pending.getId(), text));
            log.warn("评分落库失败，已归还认领：session={}, seq={}, error={}",
                    sessionId, pending.getSeq(), ex.toString());
            throw ex;
        }
        if (evaluated == null) {
            tx.execute(status -> turns.releaseAnswer(pending.getId(), text));
            throw new IllegalStateException("answer was not persisted");
        }
        listener.onEvent("evaluation", toView(evaluated));

        List<InterviewTurn> all = turns.findBySessionId(sessionId);
        InterviewPlan plan = planOrEmpty(session);
        // 主问题 = 开场题 + 考察点题；追问不计入配额
        int answeredMains = (int) all.stream()
                .filter(t -> !KIND_FOLLOW_UP.equals(t.getKind()) && t.getAnswer() != null)
                .count();

        // 下一步是追问、换题还是收尾，由纯函数决定（见 InterviewFlowPolicy，那里有逐条测试）
        InterviewFlowPolicy.Step step = InterviewFlowPolicy.decide(
                KIND_OPENING.equals(pending.getKind()), finalScore, session.getFollowUpThreshold(),
                pending.getDepth(), session.getFollowUpLimit(), answeredMains, session.getQuestionCount());
        try {
            return switch (step) {
                case FOLLOW_UP -> askFollowUp(session, plan, pending, all, evaluated, text, listener);
                case FINISH -> finishSession(session, all, evaluated, listener);
                case NEXT_MAIN -> askNextMain(session, plan, pending, all, evaluated, listener);
            };
        } catch (RuntimeException ex) {
            // 走到这里说明答案和分数都已经落库，只是"下一步"没生成出来
            // （出题模型挂了 / 报告写不出来 / 一次 DB 抖动）。
            // 如果不兜住，用户看到的就是 500，而且再答是 409：整场面试废掉，
            // 之前答的十几题全部作废。宁可出一道规则兜底题，也不能让她卡死在这里。
            log.warn("会话 {} 的下一步生成失败（step={}），降级收尾：{}", sessionId, step, ex.toString());
            return recover(session, all, evaluated, step);
        }
    }

    /**
     * 「下一步」生成失败时的收尾。
     *
     * <p>两种走向：本该出下一题 → 用规则兜底题顶上，面试继续；本该收尾 → 直接标完成
     * （报告为空，报告页会显示"总体评价还没生成"，但逐题分数都在）。</p>
     */
    private AnswerResult recover(InterviewSession session, List<InterviewTurn> all, InterviewTurn answered,
                                 InterviewFlowPolicy.Step step) {
        if (step == InterviewFlowPolicy.Step.FINISH) {
            tx.executeWithoutResult(status -> {
                InterviewSession fresh = sessions.findById(session.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                fresh.markCompleted(null, session.getOverallScore());
                sessions.save(fresh);
            });
            return new AnswerResult(toView(answered), null, InterviewStatus.COMPLETED,
                    session.getOverallScore(), null);
        }
        InterviewTurn next;
        try {
            next = saveNextTurn(session, all, null, "综合", "MEDIUM",
                    "换个角度：挑一件你最有把握说清楚的技术决策，讲讲当时的备选方案是什么、为什么最后选了这一个。",
                    "上一题的下一步生成失败，用这道兜底题保证面试能继续", "[]", "MAIN", null, 0);
        } catch (RuntimeException ex) {
            // 连兜底题都写不进去：说明 DB 本身不可用了，这时候抛出去才是诚实的
            log.error("兜底题写入失败：session={}", session.getId(), ex);
            throw ex;
        }
        return new AnswerResult(toView(answered), toQuestion(next), InterviewStatus.IN_PROGRESS,
                session.getOverallScore(), null);
    }

    /**
     * 用户主动放弃这场面试。
     *
     * <p>没有这个出口之前，用户关掉浏览器就等于把会话永久留在 IN_PROGRESS：
     * 历史页一直显示"进行中"、报告永远 409、语音票据还能继续签发。</p>
     */
    public SessionView abandon(Long sessionId) {
        InterviewSession session = requireOwned(sessionId);
        if (!session.isSettled()) {
            tx.executeWithoutResult(status -> {
                InterviewSession fresh = sessions.findById(sessionId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                fresh.markAbandoned();
                sessions.save(fresh);
            });
        }
        return detail(sessionId);
    }

    private InterviewSession requireOwned(Long sessionId) {
        CurrentActor actor = actors.requireCurrent();
        return sessions.findById(sessionId)
                .filter(session -> ResumeAccessGuard.visible(session.getOwnerUserId(), actor))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private InterviewSession requireOpenSession(Long sessionId) {
        InterviewSession session = requireOwned(sessionId);
        if (session.isSettled()) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return session;
    }

    /**
     * 就同一个考察点追问。
     *
     * <p>{@code answer} 必须由调用方显式传进来，不能读 {@code last.getAnswer()}：
     * {@code last} 是认领之前查出来的那一行，它的 {@code answer} 始终是 null。
     * 之前就是这么写的，于是模型每次都被告诉"候选人没有回答"，追问自然退化成
     * 把原题再问一遍（实测：「你刚才没回答。既然写精通Java，请具体说……」，
     * 而候选人明明整段都在讲 Java 17）。</p>
     */
    private AnswerResult askFollowUp(InterviewSession session, InterviewPlan plan, InterviewTurn last,
                                     List<InterviewTurn> all, InterviewTurn answered, String answer,
                                     InterviewProgressListener listener) {
        long start = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "就同一考察点追问"));
        int rootSeq = "MAIN".equals(last.getKind()) ? last.getSeq()
                : (last.getParentSeq() == null ? last.getSeq() : last.getParentSeq());
        List<InterviewTurn> chain = all.stream()
                .filter(t -> Objects.equals(t.getPointId(), last.getPointId()))
                .toList();
        QuestionDraft draft = interviewer.followUp(plan.point(last.getPointId()), chain, answer);
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "追问",
                "第 " + (nextSeq(all)) + " 题", 1, start));

        InterviewTurn next = saveNextTurn(session, all, last.getPointId(), last.getSkill(),
                last.getDifficulty(), draft.question(), draft.intent(), "[]", KIND_FOLLOW_UP,
                rootSeq, last.getDepth() + 1);
        log.info("Follow-up asked: session={}, seq={}, depth={}, point={}",
                session.getId(), next.getSeq(), next.getDepth(), next.getPointId());
        return new AnswerResult(toView(answered), toQuestion(next), InterviewStatus.IN_PROGRESS,
                session.getOverallScore(), null);
    }

    private AnswerResult askNextMain(InterviewSession session, InterviewPlan plan, InterviewTurn last,
                                     List<InterviewTurn> all, InterviewTurn answered,
                                     InterviewProgressListener listener) {
        long start = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Interviewer", "出下一题"));
        QuestionDraft draft = interviewer.next(plan, all);
        listener.onEvent("agent", AgentEvent.finished("Interviewer", "出题",
                "第 " + nextSeq(all) + " 题", 1, start));

        InterviewPlan.Point point = plan.point(draft.pointId());
        InterviewTurn next = saveNextTurn(session, all, draft.pointId(),
                point == null ? last.getSkill() : point.skill(), draft.difficulty(), draft.question(),
                draft.intent(), write(draft.followUps()), "MAIN", null, 0);
        return new AnswerResult(toView(answered), toQuestion(next), InterviewStatus.IN_PROGRESS,
                session.getOverallScore(), null);
    }

    /** 新题落库 + 会话推进：一次短事务，模型调用已经在外面做完了。 */
    private InterviewTurn saveNextTurn(InterviewSession session, List<InterviewTurn> all, String pointId,
                                       String skill, String difficulty, String question, String intent,
                                       String followUps, String kind, Integer parentSeq, int depth) {
        int seq = nextSeq(all);
        InterviewTurn saved = tx.execute(status -> {
            InterviewTurn turn = turns.save(InterviewTurn.ask(session.getId(), seq, pointId, skill, difficulty,
                    question, intent, followUps, kind, parentSeq, depth));
            InterviewSession fresh = sessions.findById(session.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            fresh.markInProgress(seq);
            sessions.save(fresh);
            return turn;
        });
        if (saved == null) {
            throw new IllegalStateException("next question was not persisted");
        }
        return saved;
    }

    private static int nextSeq(List<InterviewTurn> all) {
        return all.stream().mapToInt(InterviewTurn::getSeq).max().orElse(0) + 1;
    }

    private AnswerResult finishSession(InterviewSession session, List<InterviewTurn> all, InterviewTurn last,
                                       InterviewProgressListener listener) {
        long start = System.currentTimeMillis();
        // 先出总评：流式、短、几秒内用户就能读到结论，不用盯着"生成报告中"转圈。
        // 它失败不影响主流程——下面结构化报告里还有一份 summary 兜着。
        listener.onEvent("agent", AgentEvent.started("Report", "生成总评"));
        String streamedSummary = reporter.streamSummary(all,
                chunk -> listener.onEvent("report-summary", Map.of("delta", chunk)));
        listener.onEvent("agent", AgentEvent.started("Report", "生成分项评价"));
        // 报告是最后一次模型调用：失败要降级成规则报告，否则这一场永远收不了尾
        InterviewReportData produced = reporter.report(all);
        // 流式总评成功就用它（用户已经读过的那版），失败才用结构化里的
        InterviewReportData data = streamedSummary.isBlank()
                ? produced
                : new InterviewReportData(streamedSummary, produced.strengths(),
                        produced.improvements(), produced.skillScores());
        // 开场自我介绍只用来起话头，分数不参与总分，免得被"表达分"拉低技术评价
        int overall = (int) Math.round(all.stream()
                .filter(t -> !KIND_OPENING.equals(t.getKind()))
                .map(InterviewTurn::getFinalScore)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0));
        String reportJson = write(new ReportPayload(data, clampScore(overall), planOrEmpty(session).fit()));
        tx.executeWithoutResult(status -> {
            InterviewSession fresh = sessions.findById(session.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            fresh.markCompleted(reportJson, clampScore(overall));
            sessions.save(fresh);
        });
        listener.onEvent("agent", AgentEvent.finished("Report", "报告", "已生成", 1, start));
        listener.onEvent("report", read(reportJson));
        return new AnswerResult(toView(last), null, InterviewStatus.COMPLETED, clampScore(overall),
                read(reportJson));    }

    public SessionView detail(Long sessionId) {
        InterviewSession session = requireOwned(sessionId);
        List<TurnView> views = turns.findBySessionId(sessionId).stream().map(this::toView).toList();
        return new SessionView(session.getId(), session.getResumeId(), session.getStatus(),
                session.getTurnBudget(), session.getQuestionCount(), session.getFollowUpLimit(),
                session.getPersona(), session.getCurrentTurn(), session.getOverallScore(),
                session.getCreatedAt(), views);
    }

    /** 历史记录：按创建时间倒序，带一句报告摘要便于列表展示。 */
    public List<SessionSummary> list() {
        CurrentActor actor = actors.requireCurrent();
        List<InterviewSession> rows = actor.isPlatformAdmin()
                ? sessions.findAll()
                : sessions.findAllByOwner(actor.userId());
        return rows.stream().map(session -> new SessionSummary(
                session.getId(), session.getResumeId(), session.getStatus(),
                session.getQuestionCount(), session.getFollowUpLimit(),
                session.getOverallScore(), summaryOf(session.getReportJson()),
                session.getCreatedAt())).toList();
    }

    public JsonNode report(Long sessionId) {
        InterviewSession session = requireOwned(sessionId);
        if (session.getReportJson() == null) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return read(session.getReportJson());
    }

    private ReviewDecision reviewTurn(InterviewTurn turn, String answer, TurnEvaluation evaluation,
                                      InterviewProgressListener listener) {
        long reviewStart = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Reviewer", "复核评分与证据是否矛盾"));
        ReviewDecision review = reviewer.review(turn.getQuestion(), answer, evaluation);
        listener.onEvent("agent", AgentEvent.finished("Reviewer", "复核",
                review.adjusted() ? "已调整" : "维持原分", review.adjustments().size(), reviewStart));
        return review;
    }

    /**
     * 同一轮回答交给两位评委并行打分。
     *
     * <p>技术评委（{@link EvaluatorAgent}）的分决定成绩；表达评委（{@link ExpressionJudgeAgent}）
     * 不看技术、也看不到技术评委的打分，只提供第二视角。两者的分差会写进
     * {@code scores_json.judges} 供报告页展示——"懂但讲不清"和"讲得漂亮但没内容"
     * 靠单评委是分不出来的。</p>
     *
     * <p>表达评委失败只丢一个视角，不拖垮本轮：它和最核心的技术评分是并行分支，
     * 而不是串行依赖。</p>
     */
    private JudgePanel judge(InterviewTurn turn, String answer, InterviewProgressListener listener) {
        long start = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("Evaluator", "评分并提取证据"));
        listener.onEvent("agent", AgentEvent.started("ExpressionJudge", "从表达角度独立评分"));
        // 子线程拿不到父线程的 SecurityContext，评分要并行、用量要按 actor 记账，
        // 所以提交前显式把上下文带过去
        Future<TurnEvaluation> technical = judgeExecutor.submit(SecurityContextPropagation.wrap(
                () -> evaluator.evaluate(turn.getQuestion(), turn.getSkill(), answer)));
        Future<TurnEvaluation> expression = judgeExecutor.submit(SecurityContextPropagation.wrap(
                () -> expressionJudge.evaluate(turn.getQuestion(), turn.getSkill(), answer)));
        TurnEvaluation judged = await(technical);
        TurnEvaluation second = awaitOrNull(expression);
        listener.onEvent("agent", AgentEvent.finished("Evaluator", "评分",
                "第 " + turn.getSeq() + " 题", 1, start));
        listener.onEvent("agent", AgentEvent.finished("ExpressionJudge", "评分",
                second == null ? "本轮跳过" : "第 " + turn.getSeq() + " 题", 1, start));
        return new JudgePanel(judged, second);
    }

    private static TurnEvaluation await(Future<TurnEvaluation> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("evaluation interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("evaluation failed", cause);
        }
    }

    private TurnEvaluation awaitOrNull(Future<TurnEvaluation> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("ExpressionJudge 失败，本题只保留技术评委：{}", cause.getMessage());
            return null;
        }
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
        if (summary.length() <= 120) {
            return summary;
        }
        // 按 char 截断会把 emoji 的代理对切成两半，前端渲染出一个孤立的半个字符
        int end = Character.isHighSurrogate(summary.charAt(119)) ? 119 : 120;
        return summary.substring(0, end) + "…";
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
            return clampScore(evaluation.scores().weightedTotal());
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("accuracy", evaluation.scores().accuracy());
        scores.put("depth", evaluation.scores().depth());
        scores.put("completeness", evaluation.scores().completeness());
        scores.put("clarity", evaluation.scores().clarity());
        for (ReviewDecision.Adjustment adjustment : review.adjustments()) {
            if (adjustment.axis() != null && scores.containsKey(adjustment.axis())) {
                scores.put(adjustment.axis(), clampScore(adjustment.to()));
            }
        }
        return clampScore((int) Math.round(scores.get("accuracy") * 0.40 + scores.get("depth") * 0.25
                + scores.get("completeness") * 0.20 + scores.get("clarity") * 0.15));
    }

    /** 模型偶尔会给出 105 或 -3：越界分数会一路显示到报告页，落库前统一夹住。 */
    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private static String feedbackOf(TurnEvaluation evaluation, ReviewDecision review) {
        String feedback = evaluation.feedback() == null ? "" : evaluation.feedback();
        if (review != null && review.adjusted() && review.reason() != null) {
            feedback = feedback + "\n复核意见：" + review.reason();
        }
        return feedback;
    }

    /**
     * 岗位匹配。JD 只是增益：模型不听话或者调用失败，就退回纯简历面试。
     * 一段 JD 解析不出来，不该让整场面试开不了场。
     */
    private JobFit analyzeFit(String jd, ResumeAnalysisService.AnalysisView analysis,
                              InterviewProgressListener listener) {
        if (jd == null || jd.isBlank()) {
            return null;
        }
        long start = System.currentTimeMillis();
        listener.onEvent("agent", AgentEvent.started("JobFit", "对照岗位 JD 找匹配与缺口"));
        try {
            JobFit fit = jobFit.analyze(jd, analysis);
            listener.onEvent("agent", AgentEvent.finished("JobFit", "岗位匹配",
                    fit.headline() == null ? "已比对" : fit.headline(),
                    fit.matched().size() + fit.gaps().size(), start));
            return fit;
        } catch (RuntimeException ex) {
            log.warn("JobFit failed, falling back to resume-only interview: {}", ex.toString());
            listener.onEvent("agent", AgentEvent.finished("JobFit", "岗位匹配", "已跳过", 0, start));
            return null;
        }
    }

    /** 会话里存的考察点矩阵；老数据解析失败按空处理——报告不该因此打不开。 */
    private InterviewPlan planOrEmpty(InterviewSession session) {
        try {
            return readPlan(session.getPlanJson());
        } catch (RuntimeException ex) {
            return new InterviewPlan(null, List.of());
        }
    }

    /** JD 原文可能很长：留前 2 万字够模型用了，也免得 plan_json 被一段 JD 撑爆。 */
    private static String normalizeJd(String jd) {
        if (jd == null || jd.isBlank()) {
            return null;
        }
        String trimmed = jd.strip();
        return trimmed.length() <= MAX_JD_CHARS ? trimmed : trimmed.substring(0, MAX_JD_CHARS);
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
                read(turn.getReviewJson()), parseFollowUps(turn.getFollowUps()), turn.getIntent());
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

    public record ReportPayload(InterviewReportData report, int overallScore, JobFit fit) {
    }

    /** 一轮回答的两位评委；{@code expression} 为 null 表示表达评委本轮失败、已降级。 */
    private record JudgePanel(TurnEvaluation technical, TurnEvaluation expression) {
    }

    public record SessionView(Long id, Long resumeId, InterviewStatus status, int turnBudget,
                              int questionCount, int followUpLimit, String persona, int currentTurn,
                              Integer overallScore,
                              java.time.Instant createdAt, List<TurnView> turns) {
    }

    public record SessionSummary(Long id, Long resumeId, InterviewStatus status, int questionCount,
                                 int followUpLimit, Integer overallScore, String summary,
                                 java.time.Instant createdAt) {
    }

    public record TurnView(int seq, String kind, Integer parentSeq, int depth, String question, String skill,
                           String difficulty, String answer, Integer score, String feedback,
                           JsonNode evaluation, JsonNode review, List<String> followUps, String intent) {
    }

    public record QuestionView(int seq, String kind, Integer parentSeq, int depth, String question,
                               String skill, String difficulty, List<String> followUps) {
    }

    public record AnswerResult(TurnView evaluatedTurn, QuestionView nextQuestion, InterviewStatus status,
                               Integer overallScore, JsonNode report) {
    }
}
