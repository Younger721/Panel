package com.panel.interview.domain.repository;

import com.panel.interview.domain.model.InterviewTurn;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 问答轮次仓储。 */
public interface InterviewTurnRepository {

    InterviewTurn save(InterviewTurn turn);

    Optional<InterviewTurn> findById(Long id);

    List<InterviewTurn> findBySessionId(Long sessionId);

    /** 当前待作答的一题（没有则说明该出下一题或已结束）。 */
    Optional<InterviewTurn> findPending(Long sessionId);

    Optional<InterviewTurn> findBySessionIdAndSeq(Long sessionId, int seq);

    /**
     * 原子认领：只有"还没被答过"的行才会被写入答案。
     *
     * <p>返回 1 表示认领成功，0 表示这一题已经被另一个并发请求（双击、前端重试、
     * 两个标签页）认领走了。这是幂等键：同一题绝不会被评两次分、也不会出两道下一题。</p>
     */
    int claimAnswer(Long turnId, String answer, Instant answeredAt);

    /** 认领失败回滚（模型阶段抛错时把答案归还，用户可以重答，不必重开一场）。 */
    int releaseAnswer(Long turnId, String answer);
}
