package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.InterviewSession;
import com.nova.interview.domain.repository.InterviewSessionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaInterviewSessionRepository implements InterviewSessionRepository {

    private final SpringDataInterviewSessionRepository delegate;

    public JpaInterviewSessionRepository(SpringDataInterviewSessionRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public InterviewSession save(InterviewSession session) {
        return delegate.save(session);
    }

    @Override
    public Optional<InterviewSession> findById(Long id) {
        return delegate.findById(id);
    }

    @Override
    public List<InterviewSession> findAll() {
        return delegate.findAllByOrderByCreatedAtDesc();
    }
}
