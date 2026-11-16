package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.DocumentStatus;
import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbDocument;
import com.panel.knowledge.domain.model.KbItemType;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.persistence.KbCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbGraphStore;
import com.panel.knowledge.infrastructure.persistence.KbItemCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.knowledge.infrastructure.search.KbIndexer;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class KbDocumentService {

    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_DOCUMENTS_PER_KB = 500;
    private static final int MAX_PAGE_SIZE = 100;

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeObjectStore store;
    private final KbIngestService ingest;
    private final KbIndexer indexer;
    private final KbQuestionStore questions;
    private final KbItemCategoryStore itemCategories;
    private final KbCategoryStore categories;
    private final KbGraphStore graph;
    private final KbAccessGuard guard;

    public KbDocumentService(KbDocumentRepository documents,
                             KbChunkRepository chunks,
                             KnowledgeBaseRepository bases,
                             KnowledgeObjectStore store,
                             KbIngestService ingest,
                             KbIndexer indexer,
                             KbQuestionStore questions,
                             KbItemCategoryStore itemCategories,
                             KbCategoryStore categories,
                             KbGraphStore graph,
                             KbAccessGuard guard) {
        this.documents = documents;
        this.chunks = chunks;
        this.bases = bases;
        this.store = store;
        this.ingest = ingest;
        this.indexer = indexer;
        this.questions = questions;
        this.itemCategories = itemCategories;
        this.categories = categories;
        this.graph = graph;
        this.guard = guard;
    }

    private KnowledgeBase requireReadableBase(Long kbId) {
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在"));
        guard.ensureReadable(base);
        return base;
    }

    public KbDocumentView upload(Long kbId, MultipartFile file, Long actorId) {
        requireReadableBase(kbId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择要上传的文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "读取上传文件失败");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单个文件不能超过 20MB");
        }
        if (documents.countActiveByKbId(kbId) >= MAX_DOCUMENTS_PER_KB) {
            throw new BusinessException(ErrorCode.CONFLICT, "单个知识库最多 " + MAX_DOCUMENTS_PER_KB + " 份文档");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String contentType = file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream"
                : file.getContentType();
        String hash = sha256(bytes);
        if (documents.existsByKbIdAndContentHash(kbId, hash)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该文件已存在于当前知识库");
        }
        String objectKey = "kb/" + kbId + "/" + UUID.randomUUID() + "/" + fileName;
        store.put(objectKey, bytes, contentType);
        KbDocument document = KbDocument.create(kbId, fileName, contentType, bytes.length, hash, objectKey, actorId);
        documents.save(document);
        ingest.enqueue(document.getId());
        return KbDocumentView.from(document);
    }

    public KbDocumentPage list(Long kbId, String status, String keyword, int page, int size) {
        requireReadableBase(kbId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        DocumentStatus filter = parseStatus(status);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<KbDocumentView> items = documents
                .findPage(kbId, filter, kw, (safePage - 1) * safeSize, safeSize)
                .stream().map(KbDocumentView::from).toList();
        long total = documents.count(kbId, filter, kw);
        return new KbDocumentPage(items, total, safePage, safeSize);
    }

    public KbDocumentView get(Long documentId) {
        return KbDocumentView.from(require(documentId));
    }

    public KbChunkView chunk(Long chunkId) {
        List<KbChunk> found = chunks.findByIds(List.of(chunkId));
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "切片不存在：" + chunkId);
        }
        return KbChunkView.from(found.get(0));
    }

    public KbChunkPage chunks(Long documentId, int page, int size) {
        require(documentId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        List<KbChunkView> items = chunks
                .findPageByDocumentId(documentId, (safePage - 1) * safeSize, safeSize)
                .stream().map(KbChunkView::from).toList();
        long total = chunks.countByDocumentId(documentId);
        return new KbChunkPage(items, total, safePage, safeSize);
    }

    @Transactional
    public KbDocumentView retry(Long documentId) {
        KbDocument document = require(documentId);
        if (document.status() != DocumentStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败的文档可以重试");
        }
        document.resetForRetry();
        documents.save(document);
        enqueueAfterCommit(documentId);
        return KbDocumentView.from(document);
    }

    /** 重建：从原文件重新解析、切片、建索引（题库也会重新拆题）。 */
    @Transactional
    public KbDocumentView reindex(Long documentId) {
        KbDocument document = require(documentId);
        if (document.status() != DocumentStatus.READY) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有就绪的文档可以重建索引");
        }
        document.resetForRetry();
        documents.save(document);
        enqueueAfterCommit(documentId);
        return KbDocumentView.from(document);
    }

    @Transactional
    public KbDocumentView publish(Long documentId, boolean published) {
        KbDocument document = require(documentId);
        if (published) {
            document.publish();
        } else {
            document.unpublish();
        }
        return KbDocumentView.from(documents.save(document));
    }

    @Transactional
    public void delete(Long documentId) {
        KbDocument document = require(documentId);
        List<Long> chunkIds = chunks.findByDocumentIdOrderByOrdinal(documentId).stream()
                .map(KbChunk::getId)
                .toList();
        List<Long> questionIds = questions.findByDocumentId(documentId).stream()
                .map(question -> question.getId())
                .toList();
        itemCategories.deleteByItems(KbItemType.CHUNK, chunkIds);
        itemCategories.deleteByItems(KbItemType.QUESTION, questionIds);
        indexer.deleteByDocument(documentId);
        graph.deleteByDocument(documentId);
        questions.deleteByDocument(documentId);
        chunks.deleteByDocumentId(documentId);
        store.delete(document.objectKey());
        document.markDeleted();
        documents.save(document);
    }

    public byte[] download(Long documentId) {
        KbDocument document = require(documentId);
        byte[] bytes = store.read(document.objectKey());
        if (bytes == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "原文件不存在");
        }
        return bytes;
    }

    /** 删除知识库时清空它的文档、切片、对象与索引。 */
    @Transactional
    public void deleteAllForKb(Long kbId) {
        indexer.deleteByKb(kbId);
        itemCategories.deleteByKb(kbId);
        categories.deleteByKb(kbId);
        for (KbDocument document : documents.findAllByKbId(kbId)) {
            graph.deleteByDocument(document.getId());
            questions.deleteByDocument(document.getId());
            store.delete(document.objectKey());
            documents.delete(document);
        }
        chunks.deleteByKbId(kbId);
    }

    private KbDocument require(Long documentId) {
        return documents.findById(documentId)
                .filter(document -> document.status() != DocumentStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在"));
    }

    private static DocumentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的状态：" + status);
        }
    }

    private void enqueueAfterCommit(Long documentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ingest.enqueue(documentId);
                }
            });
        } else {
            ingest.enqueue(documentId);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String safeFileName(String original) {
        String raw = original == null ? "document" : original.trim().replace('\\', '/');
        int slash = raw.lastIndexOf('/');
        String base = slash >= 0 ? raw.substring(slash + 1) : raw;
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            base = "document";
        }
        StringBuilder safe = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_') {
                safe.append(ch);
            } else {
                safe.append('_');
            }
        }
        String result = safe.toString();
        return result.length() <= 200 ? result : result.substring(0, 200);
    }
}
