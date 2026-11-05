package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.InterviewTurn;
import com.nova.interview.domain.repository.InterviewTurnRepository;
import org.springframework.stereotype.Repository;

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
}
