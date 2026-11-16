package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.ResumeAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface SpringDataResumeAnalysisRepository extends JpaRepository<ResumeAnalysis, Long> {

    Optional<ResumeAnalysis> findByResumeId(Long resumeId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ResumeAnalysis a where a.resumeId = :resumeId")
    int deleteAllByResumeId(@Param("resumeId") Long resumeId);
}
