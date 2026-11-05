package com.nova.interview.infrastructure.persistence;

import com.nova.interview.domain.model.Resume;
import com.nova.interview.domain.repository.ResumeRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaResumeRepository implements ResumeRepository {

    private final SpringDataResumeRepository delegate;

    public JpaResumeRepository(SpringDataResumeRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Resume save(Resume resume) {
        return delegate.save(resume);
    }

    @Override
    public Optional<Resume> findById(Long id) {
        return delegate.findById(id);
    }

    @Override
    public List<Resume> listRecent() {
        return delegate.findTop50ByOrderByIdDesc();
    }

    @Override
    public Optional<Resume> findByContentHash(String contentHash) {
        return delegate.findByContentHash(contentHash);
    }
}
