package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.ResumeAnalysis;
import com.panel.interview.domain.repository.ResumeAnalysisRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional
    public void deleteByResumeId(Long resumeId) {
        spring.deleteAllByResumeId(resumeId);
    }
}
