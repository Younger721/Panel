package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataKbChunkRepository extends JpaRepository<KbChunk, Long> {

    @Query("select c.id from KbChunk c where c.kbId = :kbId")
    java.util.List<Long> findIdsByKbId(@Param("kbId") Long kbId);

    java.util.List<KbChunk> findByDocumentIdOrderByOrdinalAsc(Long documentId);

    Page<KbChunk> findByDocumentIdOrderByOrdinalAsc(Long documentId, Pageable pageable);

    long countByDocumentId(Long documentId);

    long countByKbId(Long kbId);

    void deleteByDocumentId(Long documentId);

    void deleteByKbId(Long kbId);
}
