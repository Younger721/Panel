package com.nova.interview.domain.repository;

import com.nova.interview.domain.model.ResumeAnalysis;

import java.util.Optional;

public interface ResumeAnalysisRepository {

    Optional<ResumeAnalysis> findByResumeId(Long resumeId);

    ResumeAnalysis save(ResumeAnalysis analysis);
}
