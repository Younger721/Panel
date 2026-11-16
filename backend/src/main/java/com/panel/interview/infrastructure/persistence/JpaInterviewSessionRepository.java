package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewStatus;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
        return delegate.findTop200ByOrderByCreatedAtDesc();
    }

    @Override
    public List<InterviewSession> findAllByOwner(Long ownerUserId) {
        return delegate.findTop200ByOwnerUserIdOrderByCreatedAtDesc(ownerUserId);
    }

    @Override
    public Optional<InterviewSession> findLatestInProgress(Long ownerUserId) {
        return ownerUserId == null
                ? Optional.empty()
                : delegate.findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, InterviewStatus.IN_PROGRESS);
    }

    @Override
    public List<InterviewSession> findStaleInProgress(Instant idleBefore) {
        return delegate.findAllByStatusAndUpdatedAtBefore(InterviewStatus.IN_PROGRESS, idleBefore);
    }

    /**
     * {@code @Transactional} 不可省：调用方是 {@code @Scheduled} 方法，本身没有事务，
     * 而 {@code @Modifying} 查询离开事务会直接抛
     * "No EntityManager with actual transaction available"。
     */
    @Override
    @Transactional
    public int abandonIfIdle(Long id, Instant idleBefore) {
        return delegate.abandonIfIdle(id, InterviewStatus.IN_PROGRESS, InterviewStatus.ABANDONED,
                idleBefore, Instant.now());
    }
}
