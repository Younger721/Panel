package com.nova.knowledge.application;

import com.nova.knowledge.domain.model.KnowledgeChunk;

import java.util.List;

public interface KnowledgeChunkRepository {
    KnowledgeChunk save(KnowledgeChunk chunk);

    List<KnowledgeChunk> findByTenantIdAndDocumentId(Long tenantId, Long documentId);

    void deleteByTenantIdAndDocumentId(Long tenantId, Long documentId);
}
