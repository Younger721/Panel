package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.application.KbDocumentRepository;
import com.panel.knowledge.domain.model.DocumentStatus;
import com.panel.knowledge.domain.model.KbDocument;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaKbDocumentRepository implements KbDocumentRepository {

    private final SpringDataKbDocumentRepository delegate;

    public JpaKbDocumentRepository(SpringDataKbDocumentRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public KbDocument save(KbDocument document) {
        return delegate.save(document);
    }

    @Override
    public Optional<KbDocument> findById(Long id) {
        return delegate.findById(id);
    }

    @Override
    public List<KbDocument> findPage(Long kbId, DocumentStatus status, String keyword, int offset, int limit) {
        return delegate.search(kbId, DocumentStatus.DELETED, status, keyword,
                PageRequest.of(Math.max(0, offset) / Math.max(1, limit), Math.max(1, limit))).getContent();
    }

    @Override
    public long count(Long kbId, DocumentStatus status, String keyword) {
        return delegate.countSearch(kbId, DocumentStatus.DELETED, status, keyword);
    }

    @Override
    public long countActiveByKbId(Long kbId) {
        return delegate.countByKbIdAndStatusNot(kbId, DocumentStatus.DELETED);
    }

    @Override
    public boolean existsByKbIdAndContentHash(Long kbId, String contentHash) {
        return delegate.existsByKbIdAndContentHashAndStatusNot(kbId, contentHash, DocumentStatus.DELETED);
    }

    @Override
    public List<KbDocument> findAllByKbId(Long kbId) {
        return delegate.findByKbIdAndStatusNot(kbId, DocumentStatus.DELETED);
    }

    @Override
    public List<KbDocument> findReadyByKbId(Long kbId) {
        return delegate.findByKbIdAndStatusOrderByIdAsc(kbId, DocumentStatus.READY);
    }

    @Override
    public List<KbDocument> findRecoverable() {
        return delegate.findByStatusIn(List.of(DocumentStatus.UPLOADING, DocumentStatus.PARSING,
                DocumentStatus.CHUNKED, DocumentStatus.INDEXING));
    }

    @Override
    public void delete(KbDocument document) {
        delegate.delete(document);
    }
}
