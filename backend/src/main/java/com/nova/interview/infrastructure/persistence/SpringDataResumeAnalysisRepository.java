package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.ResumeAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataResumeAnalysisRepository extends JpaRepository<ResumeAnalysis, Long> {

    Optional<ResumeAnalysis> findByResumeId(Long resumeId);
}
