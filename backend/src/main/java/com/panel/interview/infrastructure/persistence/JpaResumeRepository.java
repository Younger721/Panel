package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.model.ResumeStatus;
import com.panel.interview.domain.repository.ResumeRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
        return delegate.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public Optional<Resume> findAnyById(Long id) {
        return delegate.findById(id);
    }

    @Override
    public List<Resume> listRecent() {
        return delegate.findTop50ByDeletedAtIsNullOrderByIdDesc();
    }

    @Override
    public List<Resume> listRecentByOwner(Long ownerUserId) {
        return delegate.findTop50ByDeletedAtIsNullAndOwnerUserIdOrderByIdDesc(ownerUserId);
    }

    @Override
    public Optional<Resume> findByContentHash(String contentHash) {
        return delegate.findFirstByContentHashOrderByIdDesc(contentHash);
    }

    @Override
    public Optional<Resume> findByOwnerAndContentHash(Long ownerUserId, String contentHash) {
        if (ownerUserId == null) {
            return delegate.findFirstByOwnerUserIdIsNullAndContentHashOrderByIdDesc(contentHash);
        }
        return delegate.findFirstByOwnerUserIdAndContentHashOrderByIdDesc(ownerUserId, contentHash);
    }

    @Override
    public Optional<Resume> findLegacyByContentHash(String contentHash) {
        return delegate.findFirstByOwnerUserIdIsNullAndContentHashOrderByIdDesc(contentHash);
    }

    @Override
    public List<Resume> findByOwnerAndFileName(Long ownerUserId, String fileName) {
        return delegate.findByOwnerUserIdAndFileNameOrderByIdDesc(ownerUserId, fileName);
    }

    /**
     * 状态迁移。
     *
     * <p>{@code @Transactional} 必须放在这里：调用方是异步 worker，
     * 它跑在没有任何事务的虚拟线程里（模型调用不能占事务），
     * 而 {@code @Modifying} 查询离开事务会直接抛
     * "No EntityManager with actual transaction available for current thread"。</p>
     */
    @Override
    @Transactional
    public int updateStatus(Long id, ResumeStatus status, String error) {
        return delegate.updateStatus(id, status, error, Instant.now());
    }

    @Override
    public List<Resume> findStuck(Instant updatedBefore) {
        return delegate.findAllByStatusInAndUpdatedAtBefore(
                List.of(ResumeStatus.PARSING, ResumeStatus.ANALYZING), updatedBefore);
    }
}
