package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataInterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    List<InterviewSession> findAllByOrderByCreatedAtDesc();
}
