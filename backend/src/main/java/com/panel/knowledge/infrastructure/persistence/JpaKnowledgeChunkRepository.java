package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.application.KnowledgeChunkRepository;
import com.panel.knowledge.domain.model.KnowledgeChunk;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
class JpaKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final SpringDataKnowledgeChunkRepository delegate;

    JpaKnowledgeChunkRepository(SpringDataKnowledgeChunkRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public KnowledgeChunk save(KnowledgeChunk chunk) {
        return delegate.save(chunk);
    }

    @Override
    public List<KnowledgeChunk> findByTenantIdAndDocumentId(Long tenantId, Long documentId) {
        return delegate.findAllByTenantIdAndDocumentId(tenantId, documentId);
    }

    @Override
    public void deleteByTenantIdAndDocumentId(Long tenantId, Long documentId) {
        delegate.deleteAllByTenantIdAndDocumentId(tenantId, documentId);
    }
}
