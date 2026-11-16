package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;

import java.util.Collection;
import java.util.List;

public interface KbChunkRepository {

    List<KbChunk> saveAll(List<KbChunk> chunks);

    List<KbChunk> findByIds(Collection<Long> ids);

    List<Long> findIdsByKbId(Long kbId);

    List<KbChunk> findByDocumentIdOrderByOrdinal(Long documentId);

    List<KbChunk> findPageByDocumentId(Long documentId, int offset, int limit);

    long countByDocumentId(Long documentId);

    long countByKbId(Long kbId);

    void deleteByDocumentId(Long documentId);

    void deleteByKbId(Long kbId);
}
