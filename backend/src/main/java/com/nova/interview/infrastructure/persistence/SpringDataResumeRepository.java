package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface SpringDataResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findTop50ByOrderByIdDesc();

    Optional<Resume> findByContentHash(String contentHash);
}
