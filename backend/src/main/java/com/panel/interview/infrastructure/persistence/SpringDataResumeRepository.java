package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.model.ResumeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface SpringDataResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findTop50ByDeletedAtIsNullOrderByIdDesc();

    List<Resume> findTop50ByDeletedAtIsNullAndOwnerUserIdOrderByIdDesc(Long ownerUserId);

    Optional<Resume> findByIdAndDeletedAtIsNull(Long id);

    /** 含已删除：去重与"删了又传回来"的复原走这条。 */
    Optional<Resume> findFirstByContentHashOrderByIdDesc(String contentHash);

    Optional<Resume> findFirstByOwnerUserIdAndContentHashOrderByIdDesc(Long ownerUserId, String contentHash);

    Optional<Resume> findFirstByOwnerUserIdIsNullAndContentHashOrderByIdDesc(String contentHash);

    /** 同名兜底：原件已丢失的旧记录，用户重传同一个文件名时用它认领，避免再堆一条新记录。 */
    List<Resume> findByOwnerUserIdAndFileNameOrderByIdDesc(Long ownerUserId, String fileName);

    List<Resume> findAllByStatusInAndUpdatedAtBefore(Collection<ResumeStatus> statuses, Instant updatedAt);

    /** 状态迁移的窄更新：不碰 objectKey/contentHash/editLayer/deletedAt。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Resume r set r.status = :status, r.lastError = :error, r.updatedAt = :now where r.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") ResumeStatus status,
                     @Param("error") String error, @Param("now") Instant now);
}
