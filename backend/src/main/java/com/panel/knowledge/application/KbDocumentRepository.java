package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.DocumentStatus;
import com.panel.knowledge.domain.model.KbDocument;

import java.util.List;
import java.util.Optional;

public interface KbDocumentRepository {

    KbDocument save(KbDocument document);

    Optional<KbDocument> findById(Long id);

    List<KbDocument> findPage(Long kbId, DocumentStatus status, String keyword, int offset, int limit);

    long count(Long kbId, DocumentStatus status, String keyword);

    long countActiveByKbId(Long kbId);

    boolean existsByKbIdAndContentHash(Long kbId, String contentHash);

    List<KbDocument> findAllByKbId(Long kbId);

    List<KbDocument> findReadyByKbId(Long kbId);

    List<KbDocument> findRecoverable();

    void delete(KbDocument document);
}
