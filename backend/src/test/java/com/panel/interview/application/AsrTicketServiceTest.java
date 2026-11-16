package com.panel.interview.application;

import com.panel.ai.infrastructure.bailian.AsrProperties;
import com.panel.iam.domain.model.RoleCode;
import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewerPersona;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import com.panel.interview.domain.repository.InterviewTurnRepository;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsrTicketServiceTest {

    private InterviewSessionRepository sessions;
    private InterviewTurnRepository turns;
    private AsrProperties properties;
    private AsrQuotaService quota;
    private AsrTicketService service;
    private CurrentActor actor;

    @BeforeEach
    void setUp() {
        sessions = mock(InterviewSessionRepository.class);
        turns = mock(InterviewTurnRepository.class);
        properties = new AsrProperties();
        quota = new AsrQuotaService(properties);
        service = new AsrTicketService(sessions, turns, properties, quota);
        actor = new CurrentActor(7L, 1L, Set.of(RoleCode.CANDIDATE),
                Set.of("interview:answer"), 0, "jti-test");
        when(turns.findPending(anyLong())).thenReturn(Optional.of(mock(com.panel.interview.domain.model.InterviewTurn.class)));
        when(sessions.findById(anyLong())).thenReturn(Optional.of(InterviewSession.create(
                1L, 7L, 3, 1, "{}", InterviewerPersona.SERIOUS)));
    }

    /** 归属校验：别人的会话对当前用户来说就是"不存在"。 */
    @Test
    void rejectsSessionOwnedByAnotherUser() {
        when(sessions.findById(anyLong())).thenReturn(Optional.of(InterviewSession.create(
                1L, 999L, 3, 1, "{}", InterviewerPersona.SERIOUS)));

        assertThatThrownBy(() -> service.issue(actor, 42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 已放弃的会话不再签发语音券（以前只挡 COMPLETED/FAILED）。 */
    @Test
    void rejectsAbandonedSession() {
        InterviewSession abandoned = InterviewSession.create(1L, 7L, 3, 1, "{}", InterviewerPersona.SERIOUS);
        abandoned.markAbandoned();
        when(sessions.findById(anyLong())).thenReturn(Optional.of(abandoned));

        assertThatThrownBy(() -> service.issue(actor, 42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    /** 系统侧失败要能退还配额，否则用户会被一条断掉的连接扣掉当天的额度。 */
    @Test
    void refundsQuotaOnSystemFailure() {
        properties.setDailyQuotaPerUser(1);

        assertThatThrownBy(() -> {
            quota.reserveDaily(7L);
            quota.reserveDaily(7L);
        }).isInstanceOf(BusinessException.class);

        quota.refundDaily(7L);
        // 退还之后又能用一次
        quota.reserveDaily(7L);
    }

    @Test
    void issuesTicketThatCanOnlyBeUsedOnce() {
        AsrTicketService.Issued issued = service.issue(actor, 42L);

        assertThat(issued.ticket()).isNotBlank();
        assertThat(issued.model()).isEqualTo(properties.getModel());
        assertThat(issued.sampleRate()).isEqualTo(16000);
        assertThat(issued.expiresInSeconds()).isEqualTo(30);

        assertThat(service.consume(issued.ticket())).isPresent();
        // 第二次必然失败：防重放
        assertThat(service.consume(issued.ticket())).isEmpty();
    }

    @Test
    void rejectsBlankOrUnknownTicket() {
        assertThat(service.consume(null)).isEmpty();
        assertThat(service.consume("  ")).isEmpty();
        assertThat(service.consume("not-a-real-ticket")).isEmpty();
    }

    @Test
    void rejectsFinishedSession() {
        InterviewSession finished = InterviewSession.create(1L, 7L, 3, 1, "{}", InterviewerPersona.SERIOUS);
        finished.markCompleted("{\"report\":{}}", 80);
        when(sessions.findById(anyLong())).thenReturn(Optional.of(finished));

        assertThatThrownBy(() -> service.issue(actor, 42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void rejectsSessionWithoutPendingQuestion() {
        when(turns.findPending(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(actor, 42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void rejectsMissingSession() {
        when(sessions.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(actor, 42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void respectsSwitchOff() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.issue(actor, 42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void stopsAfterDailyQuota() {
        properties.setDailyQuotaPerUser(2);

        service.issue(actor, 42L);
        service.issue(actor, 42L);
        assertThatThrownBy(() -> service.issue(actor, 42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    void allowsOnlyOneConcurrentConnectionPerUser() {
        assertThat(quota.acquireConnection(7L)).isTrue();
        assertThat(quota.acquireConnection(7L)).isFalse();

        quota.releaseConnection(7L);

        assertThat(quota.acquireConnection(7L)).isTrue();
    }
}
