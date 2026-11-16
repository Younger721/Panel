package com.panel.knowledge.application;

import com.panel.ai.domain.EmbeddingGateway;
import com.panel.knowledge.domain.model.DocumentStatus;
import com.panel.knowledge.domain.model.KbDocument;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.search.KbGraphRetriever;
import com.panel.knowledge.infrastructure.search.KbReranker;
import com.panel.knowledge.infrastructure.search.KbRetriever;
import com.panel.knowledge.infrastructure.search.KbSearchFilters;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** K2 检索：文档白名单 → 双路召回（kbId 过滤）→ RRF → rerank → 阈值/每文档上限。 */
@Service
public class KbSearchService {

    private static final Logger log = LoggerFactory.getLogger(KbSearchService.class);
    private static final int RRF_K = 60;
    private static final int MAX_TOP_K = 20;

    private final KbDocumentRepository documents;
    private final KnowledgeBaseRepository bases;
    private final KbRetriever retriever;
    private final KbReranker reranker;
    private final KbSearchSettings settings;
    private final EmbeddingGateway embedding;
    private final KbGraphRetriever graphRetriever;
    private final KbGraphSettings graphSettings;
    private final KbCatalogService catalog;
    private final KbAccessGuard guard;

    public KbSearchService(KbDocumentRepository documents,
                           KnowledgeBaseRepository bases,
                           KbRetriever retriever,
                           KbReranker reranker,
                           KbSearchSettings settings,
                           EmbeddingGateway embedding,
                           KbGraphRetriever graphRetriever,
                           KbGraphSettings graphSettings,
                           KbCatalogService catalog,
                           KbAccessGuard guard) {
        this.documents = documents;
        this.bases = bases;
        this.retriever = retriever;
        this.reranker = reranker;
        this.settings = settings;
        this.embedding = embedding;
        this.graphRetriever = graphRetriever;
        this.graphSettings = graphSettings;
        this.catalog = catalog;
        this.guard = guard;
    }

    public KbSearchResult search(Long kbId, String query, int topK, String rawMode,
                                 boolean rerank, boolean publishedOnly) {
        return search(kbId, query, topK, rawMode, rerank, publishedOnly, KbSearchFilters.none(), stage -> { });
    }

    public KbSearchResult search(Long kbId, String query, int topK, String rawMode,
                                 boolean rerank, boolean publishedOnly, KbSearchFilters filters) {
        return search(kbId, query, topK, rawMode, rerank, publishedOnly, filters, stage -> { });
    }

    public KbSearchResult search(Long kbId, String query, int topK, String rawMode,
                                 boolean rerank, boolean publishedOnly, Consumer<KbSearchStage> stages) {
        return search(kbId, query, topK, rawMode, rerank, publishedOnly, KbSearchFilters.none(), stages);
    }

    /**
     * 带阶段回调的检索：同步接口传空回调，{@code /search/stream} 把它转成 SSE 的 {@code stage} 事件。
     * 回调顺序即真实执行顺序，前端据此点亮「思考链路」。
     */
    public KbSearchResult search(Long kbId, String query, int topK, String rawMode,
                                 boolean rerank, boolean publishedOnly, KbSearchFilters filters,
                                 Consumer<KbSearchStage> stages) {
        long started = System.currentTimeMillis();
        long startNano = System.nanoTime();
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId));
        guard.ensureReadable(base);
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "检索词不能为空");
        }
        String q = query.trim();
        int limit = Math.min(MAX_TOP_K, Math.max(1, topK));
        KbRetrievalMode mode;
        try {
            mode = KbRetrievalMode.parse(rawMode);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ex.getMessage());
        }

        List<KbDocument> ready = documents.findReadyByKbId(kbId).stream()
                .filter(document -> document.status() == DocumentStatus.READY)
                .filter(document -> !publishedOnly || document.published())
                .toList();
        emit(stages, new KbSearchStage("scope", "文档范围", "COMPLETED", ready.size(),
                elapsedMs(startNano), publishedOnly ? "仅已发布文档" : "含未发布文档"));
        if (ready.isEmpty()) {
            String detail = "没有可检索的 READY 文档";
            emit(stages, new KbSearchStage("keyword", "BM25 召回", "SKIPPED", 0, 0L, detail));
            emit(stages, new KbSearchStage("vector", "向量召回", "SKIPPED", 0, 0L, detail));
            emit(stages, new KbSearchStage("fusion", "RRF 融合", "SKIPPED", 0, 0L, detail));
            emit(stages, new KbSearchStage("rerank", "精排重排", "SKIPPED", 0, 0L, detail));
            emit(stages, new KbSearchStage("filter", "阈值筛选", "COMPLETED", 0, 0L, detail));
            return empty(mode, limit, started);
        }
        Map<Long, KbDocument> byId = new LinkedHashMap<>();
        ready.forEach(document -> byId.put(document.getId(), document));
        List<Long> documentIds = new ArrayList<>(byId.keySet());

        boolean degraded = false;
        int recall = settings.recallTopK();
        List<KbRetriever.ScoredChunk> keywordHits = List.of();
        List<KbRetriever.ScoredChunk> vectorHits = List.of();

        if (mode == KbRetrievalMode.VECTOR) {
            emit(stages, new KbSearchStage("keyword", "BM25 召回", "SKIPPED", 0, 0L, "当前为向量模式"));
        } else {
            long stageStart = System.nanoTime();
            emit(stages, new KbSearchStage("keyword", "BM25 召回", "RUNNING", null, null,
                    "在 READY 切片中做关键词匹配"));
            try {
                keywordHits = retriever.keyword(q, kbId, documentIds, recall, filters);
                emit(stages, new KbSearchStage("keyword", "BM25 召回", "COMPLETED", keywordHits.size(),
                        elapsedMs(stageStart), null));
            } catch (RuntimeException ex) {
                emit(stages, new KbSearchStage("keyword", "BM25 召回", "FAILED", 0,
                        elapsedMs(stageStart), "关键词检索不可用"));
                if (mode == KbRetrievalMode.KEYWORD) {
                    throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关键词检索不可用：" + ex.getMessage());
                }
                degraded = true;
                log.warn("知识库关键词召回失败，降级为向量检索 kbId={}", kbId, ex);
            }
        }
        if (mode == KbRetrievalMode.KEYWORD) {
            emit(stages, new KbSearchStage("vector", "向量召回", "SKIPPED", 0, 0L, "当前为关键词模式"));
        } else {
            long stageStart = System.nanoTime();
            emit(stages, new KbSearchStage("vector", "向量召回", "RUNNING", null, null,
                    "生成查询向量并检索 Milvus"));
            try {
                float[] vector = embedding.embed(List.of(q)).get(0);
                vectorHits = retriever.vector(vector, kbId, documentIds, recall);
                emit(stages, new KbSearchStage("vector", "向量召回", "COMPLETED", vectorHits.size(),
                        elapsedMs(stageStart), null));
            } catch (RuntimeException ex) {
                emit(stages, new KbSearchStage("vector", "向量召回", "FAILED", 0,
                        elapsedMs(stageStart), "向量检索不可用"));
                if (mode == KbRetrievalMode.VECTOR) {
                    throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "向量检索不可用：" + ex.getMessage());
                }
                degraded = true;
                log.warn("知识库向量召回失败，降级为关键词检索 kbId={}", kbId, ex);
            }
        }
        if (mode == KbRetrievalMode.HYBRID && keywordHits.isEmpty() && vectorHits.isEmpty()
                && degraded) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "两路检索都不可用：ES(9202) 与 Milvus(19530) 未启动。"
                            + "上传、切片、归类、刷题不受影响，检索测试需要先起这两个服务。");
        }

        List<KbRetriever.ScoredChunk> graphHits = List.of();
        long graphStart = System.nanoTime();
        if (!graphSettings.enabled()) {
            emit(stages, new KbSearchStage("graph-link", "图谱链接", "SKIPPED", 0, 0L, "图谱通道未启用"));
            emit(stages, new KbSearchStage("graph-expand", "图谱扩展", "SKIPPED", 0, 0L, "图谱通道未启用"));
        } else {
            emit(stages, new KbSearchStage("graph-link", "图谱链接", "RUNNING", null, null,
                    "把问题映射到库内概念"));
            KbGraphRetriever.Expansion expansion = null;
            try {
                expansion = graphRetriever.expand(q, kbId, documentIds, settings.recallTopK());
            } catch (RuntimeException ex) {
                emit(stages, new KbSearchStage("graph-link", "图谱链接", "FAILED", 0,
                        elapsedMs(graphStart), "图谱查询不可用"));
                emit(stages, new KbSearchStage("graph-expand", "图谱扩展", "SKIPPED", 0, 0L,
                        "上游失败，跳过扩展"));
                log.warn("知识库图谱召回失败，跳过该通道 kbId={}", kbId, ex);
            }
            if (expansion != null && expansion.seeds().isEmpty()) {
                emit(stages, new KbSearchStage("graph-link", "图谱链接", "SKIPPED", 0,
                        elapsedMs(graphStart), "问题里没有识别到库内概念"));
                emit(stages, new KbSearchStage("graph-expand", "图谱扩展", "SKIPPED", 0, 0L,
                        "没有可扩展的种子概念"));
            } else if (expansion != null) {
                String names = expansion.seeds().stream()
                        .map(KbGraphRetriever.LinkedConcept::name)
                        .limit(5)
                        .reduce((a, b) -> a + "、" + b)
                        .orElse("");
                emit(stages, new KbSearchStage("graph-link", "图谱链接", "COMPLETED",
                        expansion.seeds().size(), elapsedMs(graphStart), "识别到 " + names));
                long expandStart = System.nanoTime();
                emit(stages, new KbSearchStage("graph-expand", "图谱扩展", "RUNNING", null, null,
                        "沿关系找相关段落"));
                graphHits = expansion.hits();
                emit(stages, new KbSearchStage("graph-expand", "图谱扩展", "COMPLETED",
                        graphHits.size(), elapsedMs(expandStart),
                        expansion.neighbors() + " 个邻域概念 · " + graphHits.size() + " 个证据块"));
            }
        }

        long fusionStart = System.nanoTime();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        merge(candidates, keywordHits, true, byId);
        merge(candidates, vectorHits, false, byId);
        mergeGraph(candidates, graphHits, byId);
        if (base.kind() == KbKind.QUESTION_BANK && filters != null && !filters.isEmpty()
                && !candidates.isEmpty()) {
            try {
                java.util.Set<String> allowed = retriever.filterChunkKeys(kbId, candidates.keySet(), filters);
                candidates.keySet().retainAll(allowed);
            } catch (RuntimeException ex) {
                log.warn("题库元数据过滤失败，保留未过滤候选 kbId={}", kbId, ex);
            }
        }
        if (filters != null && filters.hasCategoryFilter() && !candidates.isEmpty()) {
            try {
                java.util.Set<String> allowedKeys = catalog.chunkKeysInCategories(kbId, filters.categoryIds());
                candidates.keySet().retainAll(allowedKeys);
            } catch (RuntimeException ex) {
                log.warn("目录过滤失败，保留未过滤候选 kbId={}", kbId, ex);
            }
        }

        List<Candidate> fused = new ArrayList<>(candidates.values());
        fused.forEach(candidate -> candidate.score = combine(candidate, mode));
        fused.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed()
                .thenComparingLong(c -> c.documentId)
                .thenComparingInt(c -> c.ordinal));
        int fusionLimit = Math.min(MAX_TOP_K, settings.fusionTopK());
        int beforeTrim = fused.size();
        if (fused.size() > fusionLimit) {
            fused = new ArrayList<>(fused.subList(0, fusionLimit));
        }
        emit(stages, new KbSearchStage("fusion", "RRF 融合", "COMPLETED", fused.size(),
                elapsedMs(fusionStart), "RRF k=60 · 融合前 " + beforeTrim + " 条 · 截取前 " + fusionLimit));

        boolean reranked = false;
        if (!rerank) {
            emit(stages, new KbSearchStage("rerank", "精排重排", "SKIPPED", 0, 0L, "未勾选 rerank"));
        } else if (!settings.rerankEnabled()) {
            emit(stages, new KbSearchStage("rerank", "精排重排", "SKIPPED", 0, 0L, "知识库未启用 rerank"));
        } else if (fused.isEmpty()) {
            emit(stages, new KbSearchStage("rerank", "精排重排", "SKIPPED", 0, 0L, "没有候选片段"));
        } else {
            long stageStart = System.nanoTime();
            emit(stages, new KbSearchStage("rerank", "精排重排", "RUNNING", null, null,
                    "百炼 rerank 对候选重新打分"));
            try {
                List<String> texts = fused.stream().map(c -> c.text).toList();
                for (KbReranker.Scored scored : reranker.rerank(q, texts, texts.size())) {
                    if (scored.index() >= 0 && scored.index() < fused.size()) {
                        fused.get(scored.index()).rerankScore = scored.score();
                    }
                }
                int scored = (int) fused.stream().filter(c -> c.rerankScore != null).count();
                reranked = scored > 0;
                emit(stages, new KbSearchStage("rerank", "精排重排", "COMPLETED", scored,
                        elapsedMs(stageStart), null));
            } catch (RuntimeException ex) {
                degraded = true;
                emit(stages, new KbSearchStage("rerank", "精排重排", "FAILED", 0,
                        elapsedMs(stageStart), "精排失败，沿用 RRF 排序"));
                log.warn("知识库 rerank 失败，保留 RRF 排序 kbId={}", kbId, ex);
            }
        }
        if (reranked) {
            fused.sort(Comparator
                    .comparing((Candidate c) -> c.rerankScore == null)
                    .thenComparing(Comparator.comparingDouble((Candidate c) ->
                            c.rerankScore == null ? c.score : c.rerankScore).reversed())
                    .thenComparingLong(c -> c.documentId)
                    .thenComparingInt(c -> c.ordinal));
        }

        long filterStart = System.nanoTime();
        double threshold = reranked ? Math.max(0, base.scoreThreshold().doubleValue()) : 0;
        Map<Long, Integer> perDocument = new HashMap<>();
        List<KbSearchHit> hits = new ArrayList<>();
        for (Candidate candidate : fused) {
            if (hits.size() >= limit) {
                break;
            }
            double finalScore = candidate.rerankScore == null ? candidate.score : candidate.rerankScore;
            if (reranked && candidate.rerankScore != null && candidate.rerankScore < threshold) {
                continue;
            }
            int used = perDocument.getOrDefault(candidate.documentId, 0);
            if (used >= settings.maxPerDocument()) {
                continue;
            }
            perDocument.put(candidate.documentId, used + 1);
            KbDocument document = byId.get(candidate.documentId);
            hits.add(new KbSearchHit(
                    candidate.chunkKey,
                    candidate.documentId,
                    kbId,
                    document == null ? null : document.fileName(),
                    candidate.ordinal,
                    candidate.heading,
                    candidate.pageNo,
                    candidate.text,
                    round(finalScore),
                    candidate.rerankScore == null ? null : round(candidate.rerankScore),
                    candidate.keywordRank > 0 ? candidate.keywordRank : null,
                    candidate.vectorRank > 0 ? candidate.vectorRank : null,
                    candidate.sources()));
        }
        emit(stages, new KbSearchStage("filter", "阈值筛选", "COMPLETED", hits.size(),
                elapsedMs(filterStart), reranked
                        ? "rerank 阈值 " + threshold + " · 每文档 ≤ " + settings.maxPerDocument()
                        : "未 rerank，不按阈值截断 · 每文档 ≤ " + settings.maxPerDocument()));
        return new KbSearchResult(hits, new KbSearchDebug(
                mode.name(),
                System.currentTimeMillis() - started,
                keywordHits.size(),
                vectorHits.size(),
                fused.size(),
                reranked,
                degraded,
                threshold,
                limit));
    }

    private static void emit(Consumer<KbSearchStage> stages, KbSearchStage stage) {
        if (stages != null) {
            stages.accept(stage);
        }
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000L;
    }

    private static void merge(Map<String, Candidate> candidates,
                              List<KbRetriever.ScoredChunk> hits,
                              boolean keyword,
                              Map<Long, KbDocument> documents) {
        for (KbRetriever.ScoredChunk hit : hits) {
            KbDocument document = documents.get(hit.documentId());
            if (document == null) {
                continue;
            }
            if (hit.indexGeneration() != null && document.indexGeneration() != null
                    && !Objects.equals(hit.indexGeneration(), document.indexGeneration())) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(hit.chunkKey(), key -> Candidate.from(hit));
            if (keyword) {
                candidate.keywordRank = hit.rank();
                candidate.keywordScore = hit.score();
            } else {
                candidate.vectorRank = hit.rank();
                candidate.vectorScore = hit.score();
            }
        }
    }

    private static void mergeGraph(Map<String, Candidate> candidates,
                                   List<KbRetriever.ScoredChunk> hits,
                                   Map<Long, KbDocument> documents) {
        for (KbRetriever.ScoredChunk hit : hits) {
            KbDocument document = documents.get(hit.documentId());
            if (document == null) {
                continue;
            }
            if (hit.indexGeneration() != null && document.indexGeneration() != null
                    && !Objects.equals(hit.indexGeneration(), document.indexGeneration())) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(hit.chunkKey(), key -> Candidate.from(hit));
            candidate.graphRank = hit.rank();
            candidate.graphScore = hit.score();
        }
    }

    private static double combine(Candidate candidate, KbRetrievalMode mode) {
        if (mode == KbRetrievalMode.KEYWORD) {
            return candidate.keywordScore == null ? 0 : candidate.keywordScore;
        }
        if (mode == KbRetrievalMode.VECTOR) {
            return candidate.vectorScore == null ? 0 : candidate.vectorScore;
        }
        double score = 0;
        if (candidate.keywordRank > 0) {
            score += 1.0 / (RRF_K + candidate.keywordRank);
        }
        if (candidate.vectorRank > 0) {
            score += 1.0 / (RRF_K + candidate.vectorRank);
        }
        if (candidate.graphRank > 0) {
            score += 1.0 / (RRF_K + candidate.graphRank);
        }
        return score;
    }

    private static KbSearchResult empty(KbRetrievalMode mode, int limit, long started) {
        return new KbSearchResult(List.of(), new KbSearchDebug(
                mode.name(), System.currentTimeMillis() - started, 0, 0, 0, false, false, 0, limit));
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static final class Candidate {
        private final String chunkKey;
        private final Long documentId;
        private final int ordinal;
        private final String heading;
        private final Integer pageNo;
        private final String text;
        private int keywordRank;
        private int vectorRank;
        private int graphRank;
        private Double keywordScore;
        private Double vectorScore;
        private Double graphScore;
        private Double rerankScore;
        private double score;

        private Candidate(String chunkKey, Long documentId, int ordinal, String heading, Integer pageNo, String text) {
            this.chunkKey = chunkKey;
            this.documentId = documentId;
            this.ordinal = ordinal;
            this.heading = heading;
            this.pageNo = pageNo;
            this.text = text;
        }

        private static Candidate from(KbRetriever.ScoredChunk hit) {
            return new Candidate(hit.chunkKey(), hit.documentId(), hit.ordinal(), hit.heading(),
                    hit.pageNo(), hit.text() == null ? "" : hit.text());
        }

        private List<String> sources() {
            List<String> sources = new ArrayList<>(2);
            if (keywordRank > 0) {
                sources.add("keyword");
            }
            if (vectorRank > 0) {
                sources.add("vector");
            }
            if (graphRank > 0) {
                sources.add("graph");
            }
            return sources;
        }
    }
}
