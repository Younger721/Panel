package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.InterviewTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface SpringDataInterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {

    List<InterviewTurn> findBySessionIdOrderBySeqAsc(Long sessionId);

    Optional<InterviewTurn> findFirstBySessionIdAndAnswerIsNullOrderBySeqAsc(Long sessionId);

    Optional<InterviewTurn> findBySessionIdAndSeq(Long sessionId, int seq);
}
