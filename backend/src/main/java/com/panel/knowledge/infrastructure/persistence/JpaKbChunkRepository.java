package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.application.KbChunkRepository;
import com.panel.knowledge.domain.model.KbChunk;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public class JpaKbChunkRepository implements KbChunkRepository {

    private final SpringDataKbChunkRepository delegate;

    public JpaKbChunkRepository(SpringDataKbChunkRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<KbChunk> saveAll(List<KbChunk> chunks) {
        return delegate.saveAll(chunks);
    }

    @Override
    public List<KbChunk> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return delegate.findAllById(ids);
    }

    @Override
    public List<Long> findIdsByKbId(Long kbId) {
        return delegate.findIdsByKbId(kbId);
    }

    @Override
    public List<KbChunk> findByDocumentIdOrderByOrdinal(Long documentId) {
        return delegate.findByDocumentIdOrderByOrdinalAsc(documentId);
    }

    @Override
    public List<KbChunk> findPageByDocumentId(Long documentId, int offset, int limit) {
        int page = Math.max(0, offset) / Math.max(1, limit);
        return delegate.findByDocumentIdOrderByOrdinalAsc(documentId,
                PageRequest.of(page, Math.max(1, limit))).getContent();
    }

    @Override
    public long countByDocumentId(Long documentId) {
        return delegate.countByDocumentId(documentId);
    }

    @Override
    public long countByKbId(Long kbId) {
        return delegate.countByKbId(kbId);
    }

    @Override
    @Transactional
    public void deleteByDocumentId(Long documentId) {
        delegate.deleteByDocumentId(documentId);
    }

    @Override
    @Transactional
    public void deleteByKbId(Long kbId) {
        delegate.deleteByKbId(kbId);
    }
}
