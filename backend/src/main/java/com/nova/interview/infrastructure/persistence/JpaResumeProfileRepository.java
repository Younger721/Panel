package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.ResumeProfile;
import com.nova.interview.domain.repository.ResumeProfileRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaResumeProfileRepository implements ResumeProfileRepository {

    private final SpringDataResumeProfileRepository delegate;

    public JpaResumeProfileRepository(SpringDataResumeProfileRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public ResumeProfile save(ResumeProfile profile) {
        return delegate.save(profile);
    }

    @Override
    public Optional<ResumeProfile> findByResumeId(Long resumeId) {
        return delegate.findByResumeId(resumeId);
    }
}
