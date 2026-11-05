package com.nova.interview.domain.repository;

import com.nova.interview.domain.model.InterviewSession;

import java.util.Optional;

/** 面试会话仓储。 */
public interface InterviewSessionRepository {

    InterviewSession save(InterviewSession session);

    Optional<InterviewSession> findById(Long id);
}
