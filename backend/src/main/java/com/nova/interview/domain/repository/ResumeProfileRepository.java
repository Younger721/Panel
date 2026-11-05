package com.nova.interview.domain.repository;

import com.nova.interview.domain.model.ResumeProfile;

import java.util.Optional;

/** 简历结构化画像仓储。 */
public interface ResumeProfileRepository {

    ResumeProfile save(ResumeProfile profile);

    Optional<ResumeProfile> findByResumeId(Long resumeId);
}
