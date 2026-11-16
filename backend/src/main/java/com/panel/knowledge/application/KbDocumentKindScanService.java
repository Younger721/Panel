package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbDocument;
import com.panel.knowledge.domain.model.KbItemType;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KbQuestion;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.persistence.KbItemCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 存量重判：把已经进了题库的讲义类文档重新过一遍 {@link KbDocumentKindGate}。
 *
 * <p>闸门只管新上传。库里已有的《HashMap 扩容与树化专项总结》7 道题、README 6 道题、
 * 面试口播稿 31 道题不会自己消失——重跑解析（会重切片、重建索引）代价太大，
 * 这里就地判一次：判定为知识点就删掉该文档的"题"，再把切片重新归类。</p>
 *
 * <p>默认干跑：先看"会删哪些、删几道"，人确认了才写库。</p>
 */
@Service
public class KbDocumentKindScanService {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentKindScanService.class);

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KbQuestionStore questions;
    private final KbItemCategoryStore itemCategories;
    private final KnowledgeBaseRepository bases;
    private final KbQuestionParser parser;
    private final KbDocumentKindGate gate;
    private final KbAutoClassifyService autoClassify;
    private final KbAccessGuard guard;

    public KbDocumentKindScanService(KbDocumentRepository documents,
                                     KbChunkRepository chunks,
                                     KbQuestionStore questions,
                                     KbItemCategoryStore itemCategories,
                                     KnowledgeBaseRepository bases,
                                     KbQuestionParser parser,
                                     KbDocumentKindGate gate,
                                     KbAutoClassifyService autoClassify,
                                     KbAccessGuard guard) {
        this.documents = documents;
        this.chunks = chunks;
        this.questions = questions;
        this.itemCategories = itemCategories;
        this.bases = bases;
        this.parser = parser;
        this.gate = gate;
        this.autoClassify = autoClassify;
        this.guard = guard;
    }

    @Transactional
    public ScanResult scan(Long kbId, boolean dryRun) {
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId));
        guard.ensureReadable(base);
        if (base.kind() != KbKind.QUESTION_BANK) {
            return new ScanResult(kbId, dryRun, 0, 0, 0, List.of(), "这个库不是题库，不需要重判文档类型");
        }

        List<Row> rows = new ArrayList<>();
        int demoted = 0;
        int kept = 0;
        for (KbDocument document : documents.findReadyByKbId(kbId)) {
            List<KbChunk> documentChunks = chunks.findByDocumentIdOrderByOrdinal(document.getId());
            if (documentChunks.isEmpty()) {
                continue;
            }
            String text = rebuild(documentChunks);
            List<KbQuestionDraft> drafts = parser.parse(text, documentChunks);
            KbDocumentKindGate.Verdict verdict = gate.judge(document.fileName(), text, drafts);
            List<KbQuestion> existing = questions.findByDocumentId(document.getId());
            rows.add(new Row(document.getId(), document.fileName(), documentChunks.size(), existing.size(),
                    verdict.questionBank(), verdict.score(), verdict.source(), verdict.summary()));
            if (verdict.questionBank()) {
                kept++;
                continue;
            }
            if (existing.isEmpty()) {
                continue;
            }
            demoted++;
            if (dryRun) {
                continue;
            }
            demote(kbId, document, existing);
        }
        if (!dryRun) {
            log.info("存量文档类型重判 kb={} scanned={} demoted={} kept={}", kbId, rows.size(), demoted, kept);
        }
        return new ScanResult(kbId, dryRun, rows.size(), demoted, kept, rows,
                dryRun ? "干跑：确认后再执行一次才会删题" : "已按判定结果调整");
    }

    /** 删掉这篇文档的"题"，把挂载一起摘掉，再按切片重新归类。 */
    private void demote(Long kbId, KbDocument document, List<KbQuestion> existing) {
        List<Long> questionIds = existing.stream().map(KbQuestion::getId).toList();
        itemCategories.deleteByItems(KbItemType.QUESTION, questionIds);
        questions.deleteByDocument(document.getId());
        log.info("文档类型重判为知识点 id={} file={} 删除题目={}",
                document.getId(), document.fileName(), questionIds.size());
        try {
            autoClassify.classifyDocument(kbId, document.getId());
        } catch (RuntimeException ex) {
            log.warn("重判后重新归类失败 id={} error={}", document.getId(), ex.getMessage());
        }
    }

    /** 用现存切片还原正文：heading 当标题行补回来，拆题器要靠标题认边界。 */
    static String rebuild(List<KbChunk> chunks) {
        StringBuilder text = new StringBuilder();
        for (KbChunk chunk : chunks) {
            String heading = chunk.heading() == null ? "" : chunk.heading().strip();
            String body = chunk.text() == null ? "" : chunk.text().strip();
            if (!heading.isEmpty() && !body.startsWith(heading)) {
                text.append(heading).append('\n');
            }
            text.append(body).append("\n\n");
        }
        return text.toString();
    }

    /**
     * 一篇文档的重判结论。
     *
     * @param questionBank 判成题库 = 保留现有题；false = 这篇是讲义，题要删、切片转归类
     */
    public record Row(Long documentId, String fileName, int chunkCount, int questionCount,
                      boolean questionBank, double score, String source, String evidence) {
    }

    public record ScanResult(Long kbId, boolean dryRun, int scanned, int demoted, int kept,
                             List<Row> rows, String note) {
    }
}
