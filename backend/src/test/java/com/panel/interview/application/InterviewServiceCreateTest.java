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
import com.panel.interview.agent.model.InterviewPlan;
import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewStatus;
import com.panel.interview.domain.model.InterviewTurn;
import com.panel.interview.domain.model.InterviewerPersona;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import com.panel.interview.domain.repository.InterviewTurnRepository;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建面试的三条入口守卫。
 *
 * <p>核心是"同一份简历同一时刻只能有一场进行中的面试"：串行连点靠应用层查询就能挡住，
 * 并发下两个请求都查不到对方的行，所以真正的东西是 V14 的 {@code uk_session_active}
 * 唯一索引——撞上它就说明对方刚建好，直接复用那一场。</p>
 */
class InterviewServiceCreateTest {

    private static final long USER_ID = 1L;
    private static final long RESUME_ID = 10L;
    private static final long SESSION_ID = 100L;

    private InterviewSessionRepository sessions;
    private InterviewTurnRepository turns;
    private PlannerAgent planner;
    private ResumeAccessGuard resumeAccess;
    private InterviewService service;

    @BeforeEach
    void setUp() {
        sessions = mock(InterviewSessionRepository.class);
        turns = mock(InterviewTurnRepository.class);
        planner = mock(PlannerAgent.class);
        resumeAccess = mock(ResumeAccessGuard.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        ResumeAnalysisService analysisService = mock(ResumeAnalysisService.class);

        // 平台管理员没有租户（构造器会拒绝"有租户又是平台管理员"的组合）
        when(actors.requireCurrent()).thenReturn(new CurrentActor(USER_ID, null, Set.of(RoleCode.PLATFORM_ADMIN),
                Set.of("interview:create"), 0, "jti"));
        when(planner.plan(any(), anyInt(), any())).thenReturn(
                new InterviewPlan("按简历顺序", List.of(new InterviewPlan.Point(
                        "p1", "Java", "MEDIUM", 1, List.of(), "原文片段", "批注", "MEDIUM"))));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(turns.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(turns.findBySessionId(anyLong())).thenReturn(List.of());

        service = new InterviewService(sessions, turns, analysisService, resumeAccess, actors,
                planner, mock(InterviewerAgent.class), mock(EvaluatorAgent.class),
                mock(ExpressionJudgeAgent.class), mock(ReviewerAgent.class), mock(ReportAgent.class),
                mock(JobFitAgent.class), new ObjectMapper(), directTransactions());
    }

    /** 事务直接跑：测试只关心"有没有落库"，不需要真的开事务。 */
    private static PlatformTransactionManager directTransactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    private Resume parsedResume() {
        Resume resume = Resume.create("a.pdf", "application/pdf", "resume/1/a.pdf", "hash", USER_ID);
        resume.markParsed();
        when(resumeAccess.require(RESUME_ID)).thenReturn(resume);
        return resume;
    }

    private InterviewSession runningSession() {
        InterviewSession session = InterviewSession.create(RESUME_ID, USER_ID, 3, 1,
                "{}", InterviewerPersona.SERIOUS);
        session.markInProgress(1);
        // id 由数据库生成，测试里直接给定
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        return session;
    }

    @Test
    void reusesSessionAlreadyInProgress() {
        parsedResume();
        InterviewSession running = runningSession();
        when(sessions.findLatestInProgress(USER_ID)).thenReturn(Optional.of(running));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(running));

        var view = service.create(RESUME_ID, 3, 1);

        assertThat(view.id()).isEqualTo(SESSION_ID);
        // 复用就别再烧模型：Planner 一次都不该被调用
        verify(planner, never()).plan(any(), anyInt(), any());
    }

    @Test
    void rejectsResumeThatIsNotParsedYet() {
        Resume pending = Resume.create("a.pdf", "application/pdf", "resume/1/a.pdf", "hash", USER_ID);
        when(resumeAccess.require(RESUME_ID)).thenReturn(pending);

        assertThatThrownBy(() -> service.create(RESUME_ID, 3, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("还没解析完成");
    }

    /**
     * 并发窗口：两个请求同时进来，都查不到对方的行，都去插入。
     * 后插入的那个撞唯一索引，必须回头复用对方刚建好的那一场，而不是把 500 抛给用户。
     */
    @Test
    void reusesSessionWhenInsertLosesRace() {
        parsedResume();
        InterviewSession winner = runningSession();
        when(sessions.findLatestInProgress(USER_ID))
                .thenReturn(Optional.empty())          // 第一次查：还没有
                .thenReturn(Optional.of(winner));      // 撞约束后再查：对方建好了
        when(sessions.save(any())).thenThrow(new DataIntegrityViolationException("uk_session_active"));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(winner));

        var view = service.create(RESUME_ID, 3, 1);

        assertThat(view.id()).isEqualTo(SESSION_ID);
        assertThat(view.status()).isEqualTo(InterviewStatus.IN_PROGRESS);
    }

    /** 撞了约束又查不到会话——那是真的坏了，要把原异常抛出去而不是吃掉。 */
    @Test
    void rethrowsWhenInsertFailsForAnotherReason() {
        parsedResume();
        when(sessions.findLatestInProgress(USER_ID)).thenReturn(Optional.empty());
        when(sessions.save(any())).thenThrow(new DataIntegrityViolationException("other constraint"));
        when(sessions.findById(nullable(Long.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(RESUME_ID, 3, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 会话轮次被正确落下来：开场题是一条 OPENING，不是 MAIN。 */
    @Test
    void persistsOpeningTurnOnFirstCreation() {
        parsedResume();
        when(sessions.findLatestInProgress(USER_ID)).thenReturn(Optional.empty());
        InterviewSession created = runningSession();
        when(sessions.save(any())).thenAnswer(invocation -> {
            InterviewSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                ReflectionTestUtils.setField(session, "id", SESSION_ID);
            }
            return session;
        });
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(created));
        when(turns.findPending(SESSION_ID)).thenReturn(Optional.of(openingTurn()));

        service.create(RESUME_ID, 3, 1);

        verify(turns).save(any(InterviewTurn.class));
    }

    private static InterviewTurn openingTurn() {
        return InterviewTurn.ask(SESSION_ID, 1, null, "自我介绍", "BASIC", "先做个自我介绍吧",
                "开场", "[]", "OPENING", null, 0);
    }
}
