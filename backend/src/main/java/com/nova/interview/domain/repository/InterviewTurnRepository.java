package com.nova.interview.domain.repository;

import com.nova.interview.domain.model.InterviewTurn;

import java.util.List;
import java.util.Optional;

/** 问答轮次仓储。 */
public interface InterviewTurnRepository {

    InterviewTurn save(InterviewTurn turn);

    List<InterviewTurn> findBySessionId(Long sessionId);

    /** 当前待作答的一题（没有则说明该出下一题或已结束）。 */
    Optional<InterviewTurn> findPending(Long sessionId);

    Optional<InterviewTurn> findBySessionIdAndSeq(Long sessionId, int seq);
}
