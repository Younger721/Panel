package com.panel.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.iam.domain.model.RoleCode;
import com.panel.interview.agent.EvaluatorAgent;
import com.panel.interview.agent.ExpressionJudgeAgent;
import com.panel.interview.agent.InterviewerAgent;
import com.panel.interview.agent.JobFitAgent;
import com.panel.interview.agent.PlannerAgent;
import com.panel.interview.agent.ReportAgent;
import com.panel.interview.agent.ReviewerAgent;
import com.panel.interview.agent.model.TurnEvaluation;
import com.panel.interview.agent.model.QuestionDraft;
import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewStatus;
import com.panel.interview.domain.model.InterviewTurn;
import com.panel.interview.domain.model.InterviewerPersona;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import com.panel.interview.domain.repository.InterviewTurnRepository;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作答链路的两个「不可恢复」防线。
 *
 * <p>这两处原来都会把用户卡死：答案落库了但分数没写，或者分数写了但下一题没生成——
 * 两种情况 {@code findPending} 都再也找不到那一行，用户重答只会拿到 409，
 * 整场面试连同已答的十几题一起作废。</p>
 */
class InterviewServiceAnswerRecoveryTest {

    private static final long USER_ID = 1L;
    private static final long RESUME_ID = 10L;
    private static final long SESSION_ID = 100L;
    private static final long TURN_ID = 500L;
    private static final String ANSWER = "我在电商公司负责订单中心，把超时关闭从轮询改成了延迟消息。";

    private InterviewSessionRepository sessions;
    private InterviewTurnRepository turns;
    private InterviewerAgent interviewer;
    private EvaluatorAgent evaluator;
    private ExpressionJudgeAgent expressionJudge;
    private CurrentActorProvider actors;
    private InterviewService service;
    private InterviewSession session;
    private InterviewTurn pending;

    @BeforeEach
    void setUp() {
        sessions = mock(InterviewSessionRepository.class);
        turns = mock(InterviewTurnRepository.class);
        interviewer = mock(InterviewerAgent.class);
        actors = mock(CurrentActorProvider.class);
        evaluator = mock(EvaluatorAgent.class);
        expressionJudge = mock(ExpressionJudgeAgent.class);

        when(actors.requireCurrent()).thenReturn(new CurrentActor(USER_ID, null, Set.of(RoleCode.PLATFORM_ADMIN),
                Set.of("interview:answer"), 0, "jti"));

        session = InterviewSession.create(RESUME_ID, USER_ID, 3, 1, "{}", InterviewerPersona.SERIOUS);
        session.markInProgress(1);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);

        pending = InterviewTurn.ask(SESSION_ID, 1, null, "自我介绍", "BASIC", "先做个自我介绍吧",
                "开场", "[]", "OPENING", null, 0);
        ReflectionTestUtils.setField(pending, "id", TURN_ID);

        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(turns.findPending(SESSION_ID)).thenReturn(Optional.of(pending));
        when(turns.claimAnswer(anyLong(), anyString(), any(Instant.class))).thenReturn(1);
        when(turns.findById(nullable(Long.class))).thenReturn(Optional.of(pending));
        when(turns.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(turns.findBySessionId(SESSION_ID)).thenReturn(List.of(pending));

        TurnEvaluation good = new TurnEvaluation(new TurnEvaluation.Scores(90, 90, 90, 90),
                List.of(new TurnEvaluation.Evidence("订单中心", "对应原文")), List.of(), "答得具体", true);
        when(evaluator.evaluate(anyString(), anyString(), anyString())).thenReturn(good);
        when(expressionJudge.evaluate(anyString(), anyString(), anyString())).thenReturn(good);

        service = buildService();
    }

    private InterviewService buildService() {
        return new InterviewService(sessions, turns, mock(ResumeAnalysisService.class),
                mock(ResumeAccessGuard.class), actors, mock(PlannerAgent.class), interviewer, evaluator,
                expressionJudge, mock(ReviewerAgent.class), mock(ReportAgent.class),
                mock(JobFitAgent.class), new ObjectMapper(), directTransactions());
    }

    private static PlatformTransactionManager directTransactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    /**
     * 出题模型挂了：答案与分数都已经落库，必须兜一道规则题让面试继续，
     * 而不是把 500 抛给用户（她再答一次只会得到 409）。
     */
    @Test
    void fallsBackToRuleQuestionWhenNextQuestionCannotBeGenerated() {
        when(interviewer.next(any(), any())).thenThrow(new IllegalStateException("model down"));
        when(interviewer.followUp(any(), any(), any())).thenThrow(new IllegalStateException("model down"));

        var result = service.answer(SESSION_ID, ANSWER);

        assertThat(result.status()).isEqualTo(InterviewStatus.IN_PROGRESS);
        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.nextQuestion().question()).contains("换个角度");
        // 本题的分数还是正常的：兜的只是"下一步"，不是评分
        assertThat(result.evaluatedTurn().score()).isEqualTo(90);
    }

    /**
     * 分数落库失败：认领必须归还，否则这一行变成"有回答无评分"，
     * findPending 永远找不到它，用户重答直接 409。
     */
    @Test
    void releasesClaimWhenEvaluationCannotBePersisted() {
        when(turns.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.answer(SESSION_ID, ANSWER))
                .isInstanceOf(IllegalStateException.class);

        verify(turns).releaseAnswer(TURN_ID, ANSWER);
    }

    /**
     * 追问必须拿到候选人刚说的那段话。
     *
     * <p>回归防线：{@code pending} 是认领之前查出来的行，它的 answer 永远是 null。
     * 之前追问直接读 {@code last.getAnswer()}，模型每次都被告知"候选人没有回答"，
     * 于是把原题换个说法再问一遍——实测追问输出就是
     * 「你刚才没回答。既然写精通Java，请具体说……」。</p>
     */
    @Test
    void followUpReceivesTheAnswerText() {
        // 换成主问题，并且评分离散度低到触发追问
        pending = InterviewTurn.ask(SESSION_ID, 2, "P1", "专业技能", "MEDIUM",
                "你简历写精通 Java，说清用的哪个版本。", "考察点 P1", "[]", "MAIN", null, 0);
        ReflectionTestUtils.setField(pending, "id", TURN_ID);
        when(turns.findPending(SESSION_ID)).thenReturn(Optional.of(pending));
        when(turns.findBySessionId(SESSION_ID)).thenReturn(List.of(pending));

        TurnEvaluation poor = new TurnEvaluation(new TurnEvaluation.Scores(40, 40, 40, 40),
                List.of(new TurnEvaluation.Evidence("Java 17", "原文")), List.of(), "深度不足", true);
        when(evaluator.evaluate(anyString(), anyString(), anyString())).thenReturn(poor);
        when(interviewer.followUp(any(), any(), any())).thenReturn(new QuestionDraft(
                "那 ShardingSphere 的分片键为什么选用户 ID？", "FOLLOW_UP", "MEDIUM",
                "P1", "追问分片策略", List.of()));

        service = buildService();
        var result = service.answer(SESSION_ID, ANSWER);

        ArgumentCaptor<String> answerArg = ArgumentCaptor.forClass(String.class);
        verify(interviewer).followUp(any(), any(), answerArg.capture());
        assertThat(answerArg.getValue()).isEqualTo(ANSWER);
        assertThat(result.nextQuestion().kind()).isEqualTo("FOLLOW_UP");
    }
}
