package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunkStrategy;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KbVisibility;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KbDocumentService documentService;
    private final KbAccessGuard guard;

    public KnowledgeBaseService(KnowledgeBaseRepository repository,
                                KbDocumentRepository documents,
                                KbChunkRepository chunks,
                                KbDocumentService documentService,
                                KbAccessGuard guard) {
        this.repository = repository;
        this.documents = documents;
        this.chunks = chunks;
        this.documentService = documentService;
        this.guard = guard;
    }

    public List<KnowledgeBaseView> list() {
        return repository.findAll().stream()
                .filter(guard::canRead)
                .map(base -> KnowledgeBaseView.from(base,
                        documents.countActiveByKbId(base.getId()),
                        chunks.countByKbId(base.getId())))
                .toList();
    }

    public KnowledgeBaseView get(long id) {
        KnowledgeBase base = require(id);
        guard.ensureReadable(base);
        return KnowledgeBaseView.from(base,
                documents.countActiveByKbId(id),
                chunks.countByKbId(id));
    }

    @Transactional
    public KnowledgeBaseView create(CreateKnowledgeBaseCommand command, Long actorId) {
        try {
            String code = command.code() == null ? null : command.code().trim().toLowerCase();
            if (code != null && repository.findByCode(code).isPresent()) {
                throw new BusinessException(ErrorCode.CONFLICT, "知识库 code 已存在：" + code);
            }
            KnowledgeBase base = KnowledgeBase.create(
                    command.code(),
                    command.name(),
                    command.description(),
                    KbKind.parse(command.kind()),
                    KbVisibility.parse(command.visibility()),
                    command.chunkStrategy() == null ? null : KbChunkStrategy.parse(command.chunkStrategy()),
                    command.chunkSize(),
                    command.chunkOverlap(),
                    command.topK(),
                    command.rerankEnabled(),
                    command.scoreThreshold(),
                    actorId);
            return KnowledgeBaseView.from(repository.save(base));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
    }

    @Transactional
    public KnowledgeBaseView update(long id, UpdateKnowledgeBaseCommand command) {
        KnowledgeBase base = require(id);
        try {
            if (command.name() != null || command.description() != null) {
                String name = command.name() == null ? base.name() : command.name();
                String description = command.description() == null ? base.description() : command.description();
                base.rename(name, description);
            }
            base.updateSettings(
                    command.visibility() == null ? null : KbVisibility.parse(command.visibility()),
                    command.chunkStrategy() == null ? null : KbChunkStrategy.parse(command.chunkStrategy()),
                    command.chunkSize(),
                    command.chunkOverlap(),
                    command.topK(),
                    command.rerankEnabled(),
                    command.scoreThreshold());
            return KnowledgeBaseView.from(repository.save(base));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
    }

    /**
     * 删除知识库：非空库必须显式 {@code force=true}；内置库永远不能删。
     * 强制删除会一并清掉文档、切片、MinIO 对象与 ES/Milvus 数据。
     */
    @Transactional
    public void delete(long id, boolean force) {
        KnowledgeBase base = require(id);
        if (base.isBuiltin()) {
            throw new BusinessException(ErrorCode.CONFLICT, "内置知识库不能删除");
        }
        long documentCount = documents.countActiveByKbId(id);
        if (documentCount > 0 && !force) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "知识库下还有 " + documentCount + " 份文档，确认删除请传 force=true");
        }
        documentService.deleteAllForKb(id);
        repository.delete(base);
    }

    private KnowledgeBase require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + id));
    }
}
