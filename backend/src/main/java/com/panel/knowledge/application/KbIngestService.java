package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.DocumentStatus;
import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbDocument;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KbQuestion;
import com.panel.knowledge.domain.model.KbItemType;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.parser.DocumentParserRegistry;
import com.panel.knowledge.infrastructure.parser.KbChunker;
import com.panel.knowledge.infrastructure.parser.ParsedDocument;
import com.panel.knowledge.infrastructure.persistence.KbItemCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.knowledge.infrastructure.search.KbIndexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档入库：解析 → 切片 → 落库 → 建索引。
 *
 * <p>没有放进 dispatcher 事务里：每一步单独 save，ES/Milvus 调用在事务外；失败写回 FAILED，
 * 重试从原文件重新走一遍（切片与索引都是“先删后写”，可重复执行）。</p>
 */
@Service
public class KbIngestService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestService.class);

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeObjectStore store;
    private final KbChunker chunker;
    private final KbIndexer indexer;
    private final KbQuestionParser questionParser;
    private final KbDocumentKindGate docKindGate;
    private final KbQuestionStore questionStore;
    private final KbItemCategoryStore itemCategories;
    private final KbAutoClassifyService autoClassify;
    private final KbGraphExtractionService graphExtraction;
    private final TaskExecutor executor;
    private final DocumentParserRegistry parser = DocumentParserRegistry.standard();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public KbIngestService(KbDocumentRepository documents,
                           KbChunkRepository chunks,
                           KnowledgeBaseRepository bases,
                           KnowledgeObjectStore store,
                           KbChunker chunker,
                           KbIndexer indexer,
                           KbQuestionParser questionParser,
                           KbDocumentKindGate docKindGate,
                           KbQuestionStore questionStore,
                           KbItemCategoryStore itemCategories,
                           KbAutoClassifyService autoClassify,
                           KbGraphExtractionService graphExtraction,
                           @Qualifier("kbIngestExecutor") TaskExecutor executor) {
        this.documents = documents;
        this.chunks = chunks;
        this.bases = bases;
        this.store = store;
        this.chunker = chunker;
        this.indexer = indexer;
        this.questionParser = questionParser;
        this.docKindGate = docKindGate;
        this.questionStore = questionStore;
        this.itemCategories = itemCategories;
        this.autoClassify = autoClassify;
        this.graphExtraction = graphExtraction;
        this.executor = executor;
    }

    public void enqueue(Long documentId) {
        if (documentId == null) {
            return;
        }
        executor.execute(() -> processSafely(documentId));
    }

    void processSafely(Long documentId) {
        if (!running.add(documentId)) {
            return;
        }
        try {
            process(documentId);
        } catch (Exception ex) {
            log.error("知识库文档入库失败 id={}", documentId, ex);
            fail(documentId, ex);
        } finally {
            running.remove(documentId);
        }
    }

    /** 幂等：已 READY 直接返回；FAILED/PARSING 重新解析；CHUNKED/INDEXING 直接建索引。 */
    public void process(Long documentId) {
        KbDocument document = documents.findById(documentId).orElse(null);
        if (document == null || document.status() == DocumentStatus.DELETED
                || document.status() == DocumentStatus.READY) {
            return;
        }
        if (document.status() == DocumentStatus.UPLOADING
                || document.status() == DocumentStatus.FAILED
                || document.status() == DocumentStatus.PARSING) {
            document = parseAndChunk(document);
        }
        if (document.status() == DocumentStatus.CHUNKED || document.status() == DocumentStatus.INDEXING) {
            index(document);
        }
    }

    private KbDocument parseAndChunk(KbDocument document) {
        byte[] bytes = store.read(document.objectKey());
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("原文件不存在或为空，请重新上传");
        }
        document.markParsing();
        document = documents.save(document);

        Long kbId = document.kbId();
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new IllegalStateException("知识库不存在：" + kbId));
        ParsedDocument parsed = parser.parseStructured(document.fileName(), document.contentType(), bytes);
        List<KbChunker.Draft> drafts = chunker.chunk(parsed, base.chunkStrategy(),
                base.chunkSize(), base.chunkOverlap());

        List<Long> oldChunkIds = chunks.findByDocumentIdOrderByOrdinal(document.getId()).stream()
                .map(KbChunk::getId)
                .toList();
        itemCategories.deleteByItems(KbItemType.CHUNK, oldChunkIds);
        List<Long> oldQuestionIds = questionStore.findByDocumentId(document.getId()).stream()
                .map(KbQuestion::getId)
                .toList();
        itemCategories.deleteByItems(KbItemType.QUESTION, oldQuestionIds);
        chunks.deleteByDocumentId(document.getId());        List<KbChunk> entities = new ArrayList<>(drafts.size());
        int totalTokens = 0;
        for (KbChunker.Draft draft : drafts) {
            totalTokens += draft.tokenCount();
            entities.add(KbChunk.create(document.kbId(), document.getId(), UUID.randomUUID().toString(),
                    draft.ordinal(), draft.heading(), draft.pageNo(), draft.tokenCount(), draft.text()));
        }
        chunks.saveAll(entities);
        refreshQuestions(document, entities, base, parsed.plainText());
        document.markChunked(entities.size(), Math.max(1, totalTokens));
        document = documents.save(document);
        try {
            autoClassify.classifyDocument(document.kbId(), document.getId());
        } catch (RuntimeException ex) {
            log.warn("自动归类失败，内容保留在未分类 id={} error={}", document.getId(), ex.getMessage());
        }
        log.info("知识库文档切片完成 id={} chunks={}", document.getId(), entities.size());
        return document;
    }

    private void index(KbDocument document) {
        List<KbChunk> list = chunks.findByDocumentIdOrderByOrdinal(document.getId());
        if (list.isEmpty()) {
            throw new IllegalStateException("没有切片，无法建索引");
        }
        Long kbId = document.kbId();
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new IllegalStateException("知识库不存在：" + kbId));
        List<KbQuestion> questions = questionStore.findByDocumentId(document.getId());
        document.markIndexing();
        document = documents.save(document);
        String generation = indexer.index(document, list, base.kind(), questions);
        document.markReady(generation);
        documents.save(document);
        graphExtraction.enqueue(document.getId());
        log.info("知识库文档索引完成 id={} chunks={} questions={} generation={}",
                document.getId(), list.size(), questions.size(), generation);
    }

    /**
     * 题库才解析题目；其它类型清掉旧题目，避免知识库类型切换后残留。
     *
     * <p>进题库库不等于每篇都是题库：目录里的《HashMap 扩容与树化专项总结》、README、
     * 面试口播稿都在同一个库里，按库一刀切会把讲义切成"题"，再被归类器挂进「面试 / 通用表达方法」。
     * 所以先过 {@link KbDocumentKindGate}：判定为知识点的文档不抽题，切片照常走归类。</p>
     */
    private void refreshQuestions(KbDocument document, List<KbChunk> entities, KnowledgeBase base,
                                  String plainText) {
        if (base.kind() != KbKind.QUESTION_BANK) {
            questionStore.deleteByDocument(document.getId());
            return;
        }
        // 以整篇正文拆题：切片边界不该决定这份文档能出几道题
        List<KbQuestionDraft> drafts = questionParser.parse(plainText, entities);
        KbDocumentKindGate.Verdict verdict = docKindGate.judge(document.fileName(), plainText, drafts);
        if (!verdict.questionBank()) {
            questionStore.deleteByDocument(document.getId());
            log.info("文档判定为知识点，不抽题 id={} file={} 分数={} 判定={} 依据={}",
                    document.getId(), document.fileName(), verdict.score(), verdict.source(), verdict.summary());
            return;
        }
        log.info("文档判定为题库 id={} file={} 分数={} 判定={} 依据={}",
                document.getId(), document.fileName(), verdict.score(), verdict.source(), verdict.summary());
        List<KbQuestion> questions = new ArrayList<>(drafts.size());
        for (KbQuestionDraft draft : drafts) {
            questions.add(KbQuestion.create(document.kbId(), document.getId(), draft.chunkId(),
                    draft.stem(), draft.answer(), draft.difficulty(), draft.company(), draft.role(),
                    draft.year(), tagsJson(draft.tags())));
        }
        questionStore.replaceByDocument(document.getId(), questions);
        log.info("题库解析完成 id={} questions={}", document.getId(), questions.size());
    }

    private String tagsJson(List<String> tags) {
        try {
            return mapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private void fail(Long documentId, Exception failure) {
        try {
            documents.findById(documentId).ifPresent(document -> {
                if (document.status() == DocumentStatus.DELETED || document.status() == DocumentStatus.READY) {
                    return;
                }
                String message = failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();
                document.markFailed(message);
                documents.save(document);
            });
        } catch (RuntimeException ex) {
            log.error("写回知识库文档失败状态时出错 id={}", documentId, ex);
        }
    }
}
