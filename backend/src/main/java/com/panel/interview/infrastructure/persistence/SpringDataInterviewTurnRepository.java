package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.InterviewTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface SpringDataInterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {

    List<InterviewTurn> findBySessionIdOrderBySeqAsc(Long sessionId);

    Optional<InterviewTurn> findFirstBySessionIdAndAnswerIsNullOrderBySeqAsc(Long sessionId);

    Optional<InterviewTurn> findBySessionIdAndSeq(Long sessionId, int seq);

    /** 原子认领：并发下只有一次 UPDATE 能命中 answer IS NULL。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InterviewTurn t set t.answer = :answer, t.answeredAt = :answeredAt "
            + "where t.id = :turnId and t.answer is null")
    int claimAnswer(@Param("turnId") Long turnId, @Param("answer") String answer,
                    @Param("answeredAt") Instant answeredAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InterviewTurn t set t.answer = null, t.answeredAt = null "
            + "where t.id = :turnId and t.answer = :answer")
    int releaseAnswer(@Param("turnId") Long turnId, @Param("answer") String answer);
}
