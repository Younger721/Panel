package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.ResumeAnalysis;
import com.nova.interview.domain.repository.ResumeAnalysisRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class JpaResumeAnalysisRepository implements ResumeAnalysisRepository {

    private final SpringDataResumeAnalysisRepository spring;

    JpaResumeAnalysisRepository(SpringDataResumeAnalysisRepository spring) {
        this.spring = spring;
    }

    @Override
    public Optional<ResumeAnalysis> findByResumeId(Long resumeId) {
        return spring.findByResumeId(resumeId);
    }

    @Override
    public ResumeAnalysis save(ResumeAnalysis analysis) {
        return spring.save(analysis);
    }
}
