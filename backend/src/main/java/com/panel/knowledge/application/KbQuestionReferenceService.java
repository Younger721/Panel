package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KbQuestion;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.persistence.KbGraphStore;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.knowledge.infrastructure.search.KbGraphRetriever;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题目 → 文档证据：题目题干里出现的概念，去技术文档库找到提及这些概念的片段。
 *
 * <p>P1 的“双库联动”先做读侧：不依赖题目概念表，直接用题干做实体链接，避免额外一次标注。</p>
 */
@Service
public class KbQuestionReferenceService {

    private static final int MAX_REFERENCES = 5;
    private static final int MAX_SEEDS = 3;
    private static final int MAX_TEXT = 320;

    private final KbQuestionStore questions;
    private final KnowledgeBaseRepository bases;
    private final KbGraphRetriever graphRetriever;
    private final KbGraphStore graph;
    private final KbChunkRepository chunks;
    private final KbDocumentRepository documents;

    public KbQuestionReferenceService(KbQuestionStore questions,
                                      KnowledgeBaseRepository bases,
                                      KbGraphRetriever graphRetriever,
                                      KbGraphStore graph,
                                      KbChunkRepository chunks,
                                      KbDocumentRepository documents) {
        this.questions = questions;
        this.bases = bases;
        this.graphRetriever = graphRetriever;
        this.graph = graph;
        this.chunks = chunks;
        this.documents = documents;
    }

    public List<KbQuestionReferenceView> references(Long questionId) {
        KbQuestion question = questions.findById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "题目不存在：" + questionId);
        }
        List<KbQuestionReferenceView> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (KnowledgeBase base : bases.findAll()) {
            if (base.kind() != KbKind.TECH_DOC) {
                continue;
            }
            List<KbGraphRetriever.LinkedConcept> seeds =
                    graphRetriever.link(base.getId(), question.stem());
            if (seeds.isEmpty()) {
                continue;
            }
            List<Long> conceptIds = seeds.stream()
                    .map(KbGraphRetriever.LinkedConcept::id)
                    .limit(MAX_SEEDS)
                    .toList();
            List<String> conceptNames = seeds.stream()
                    .map(KbGraphRetriever.LinkedConcept::name)
                    .limit(MAX_SEEDS)
                    .toList();
            List<Long> chunkIds = graph.chunksByConcepts(base.getId(), conceptIds, MAX_REFERENCES);
            Map<Long, KbChunk> byId = new HashMap<>();
            for (KbChunk chunk : chunks.findByIds(chunkIds)) {
                byId.put(chunk.getId(), chunk);
            }
            for (Long chunkId : chunkIds) {
                KbChunk chunk = byId.get(chunkId);
                if (chunk == null || !seen.add(chunkId)) {
                    continue;
                }
                result.add(new KbQuestionReferenceView(
                        chunk.getId(),
                        chunk.kbId(),
                        chunk.documentId(),
                        documents.findById(chunk.documentId()).map(document -> document.fileName()).orElse(null),
                        chunk.heading(),
                        truncate(chunk.text()),
                        conceptNames));
                if (result.size() >= MAX_REFERENCES) {
                    return result;
                }
            }
        }
        return result;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…";
    }
}
