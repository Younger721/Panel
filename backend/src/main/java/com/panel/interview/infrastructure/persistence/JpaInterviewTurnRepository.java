package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.InterviewTurn;
import com.panel.interview.domain.repository.InterviewTurnRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaInterviewTurnRepository implements InterviewTurnRepository {

    private final SpringDataInterviewTurnRepository delegate;

    public JpaInterviewTurnRepository(SpringDataInterviewTurnRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public InterviewTurn save(InterviewTurn turn) {
        return delegate.save(turn);
    }

    @Override
    public Optional<InterviewTurn> findById(Long id) {
        return delegate.findById(id);
    }

    @Override
    public List<InterviewTurn> findBySessionId(Long sessionId) {
        return delegate.findBySessionIdOrderBySeqAsc(sessionId);
    }

    @Override
    public Optional<InterviewTurn> findPending(Long sessionId) {
        return delegate.findFirstBySessionIdAndAnswerIsNullOrderBySeqAsc(sessionId);
    }

    @Override
    public Optional<InterviewTurn> findBySessionIdAndSeq(Long sessionId, int seq) {
        return delegate.findBySessionIdAndSeq(sessionId, seq);
    }

    @Override
    @Transactional
    public int claimAnswer(Long turnId, String answer, Instant answeredAt) {
        return delegate.claimAnswer(turnId, answer, answeredAt);
    }

    @Override
    @Transactional
    public int releaseAnswer(Long turnId, String answer) {
        return delegate.releaseAnswer(turnId, answer);
    }
}
