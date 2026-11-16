package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.DocumentStatus;
import com.panel.knowledge.domain.model.KbDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface SpringDataKbDocumentRepository extends JpaRepository<KbDocument, Long> {

    boolean existsByKbIdAndContentHashAndStatusNot(Long kbId, String contentHash, DocumentStatus status);

    long countByKbIdAndStatusNot(Long kbId, DocumentStatus status);

    List<KbDocument> findByKbIdAndStatusNot(Long kbId, DocumentStatus status);

    List<KbDocument> findByKbIdAndStatusOrderByIdAsc(Long kbId, DocumentStatus status);

    @Query("""
            select d from KbDocument d
            where d.kbId = :kbId and d.status <> :deleted
              and (:status is null or d.status = :status)
              and (:keyword is null or lower(d.fileName) like lower(concat('%', :keyword, '%')))
            order by d.id desc
            """)
    Page<KbDocument> search(@Param("kbId") Long kbId,
                            @Param("deleted") DocumentStatus deleted,
                            @Param("status") DocumentStatus status,
                            @Param("keyword") String keyword,
                            Pageable pageable);

    @Query("""
            select count(d) from KbDocument d
            where d.kbId = :kbId and d.status <> :deleted
              and (:status is null or d.status = :status)
              and (:keyword is null or lower(d.fileName) like lower(concat('%', :keyword, '%')))
            """)
    long countSearch(@Param("kbId") Long kbId,
                     @Param("deleted") DocumentStatus deleted,
                     @Param("status") DocumentStatus status,
                     @Param("keyword") String keyword);

    @Query("select d from KbDocument d where d.status in :statuses order by d.id asc")
    List<KbDocument> findByStatusIn(@Param("statuses") Collection<DocumentStatus> statuses);
}
