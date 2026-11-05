package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.ResumeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataResumeProfileRepository extends JpaRepository<ResumeProfile, Long> {

    Optional<ResumeProfile> findByResumeId(Long resumeId);
}
