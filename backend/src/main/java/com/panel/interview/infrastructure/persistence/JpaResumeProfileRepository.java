package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.ResumeProfile;
import com.panel.interview.domain.repository.ResumeProfileRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
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

    @Override
    public List<ResumeProfile> findByResumeIdIn(Collection<Long> resumeIds) {
        return resumeIds == null || resumeIds.isEmpty() ? List.of() : delegate.findByResumeIdIn(resumeIds);
    }

    @Override
    @Transactional
    public void deleteByResumeId(Long resumeId) {
        delegate.deleteAllByResumeId(resumeId);
    }
}
