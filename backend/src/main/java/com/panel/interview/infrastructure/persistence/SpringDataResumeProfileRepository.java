package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.ResumeProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface SpringDataResumeProfileRepository extends JpaRepository<ResumeProfile, Long> {

    Optional<ResumeProfile> findByResumeId(Long resumeId);

    List<ResumeProfile> findByResumeIdIn(Collection<Long> resumeIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ResumeProfile p where p.resumeId = :resumeId")
    int deleteAllByResumeId(@Param("resumeId") Long resumeId);
}
