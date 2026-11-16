package com.panel.knowledge.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.domain.EmbeddingGateway;
import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbDocument;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KbQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K1 索引写入：ES（panel-knowledge，kbId 过滤）+ Milvus（panel_knowledge）。
 *
 * <p>与旧索引的区别：带 {@code kbId}、不按 tenant 过滤、schema 不匹配时**不自动 drop** 整个 collection。</p>
 */
@Component
public class KbIndexer {

    private static final Logger log = LoggerFactory.getLogger(KbIndexer.class);
    private static final int VECTOR_DIMENSIONS = 1024;
    private static final int MILVUS_BATCH = 200;
    private static final int MAX_TEXT = 65000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EmbeddingGateway embedding;
    private final RestClient es;
    private final RestClient milvus;
    private final String indexName;
    private final String collection;
    private volatile boolean esReady;
    private volatile boolean mappingReady;
    private volatile boolean collectionReady;
    /** 外部索引后端不可用时的时间窗：窗口内直接跳过，避免每次请求都等一次连接超时。 */
    private volatile long esDownUntil;
    private volatile long milvusDownUntil;

    /** 降级索引的 generation 前缀：表示"只落库、没写外部索引"。 */
    public static final String DEGRADED_PREFIX = "local-";
    private static final long DOWN_COOLDOWN_MS = 30_000L;

    /**
     * 生产构造器（Spring 用）。
     *
     * <p>显式标注 {@code @Autowired}：本类另有一个给单测用的三参构造器，
     * 两个构造器会让人猜不出 Spring 该选哪个（会退化成"找不到默认构造器"）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    public KbIndexer(EmbeddingGateway embedding) {
        this(embedding, env("PANEL_ES_URL", "http://localhost:9202"), env("PANEL_MILVUS_URL", "http://localhost:19530"));
    }

    /** 供测试指定后端地址（环境变量在单测里改不了）。 */
    KbIndexer(EmbeddingGateway embedding, String esUrl, String milvusUrl) {
        this.embedding = embedding;
        this.es = RestClient.builder().baseUrl(esUrl).build();
        this.milvus = RestClient.builder().baseUrl(milvusUrl).build();
        this.indexName = env("PANEL_KB_ES_INDEX", "panel-knowledge");
        this.collection = env("PANEL_KB_MILVUS_COLLECTION", "panel_knowledge");
    }

    /** 写入一代索引，返回 generation；旧数据先删后写，失败可安全重试。 */
    public String index(KbDocument document, List<KbChunk> chunks, KbKind kind, List<KbQuestion> questions) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("没有可索引的切片");
        }
        boolean esOk = ensureEsIndex();
        boolean milvusOk = ensureCollection();
        if (!esOk && !milvusOk) {
            // 外部索引后端都没起来：不阻塞入库，只落库 + 关键词检索。
            String degraded = DEGRADED_PREFIX + document.getId() + "-" + System.currentTimeMillis();
            log.warn("索引后端不可用，文档 {} 只落库未建外部索引（generation={}，检索退化为关键词）",
                    document.getId(), degraded);
            return degraded;
        }
        List<float[]> vectors = embedding.embed(chunks.stream().map(KbChunk::text).toList());
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("embedding 数量与切片数量不一致");
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != VECTOR_DIMENSIONS) {
                throw new IllegalStateException("embedding 维度不是 " + VECTOR_DIMENSIONS);
            }
        }
        String generation = "g" + document.getId() + "-" + System.currentTimeMillis();
        deleteByDocument(document.getId());
        if (esOk) {
            writeEs(document, chunks, vectors, generation, kind, questions);
        } else {
            log.warn("ES 不可用，跳过关键词索引写入 documentId={}", document.getId());
        }
        if (milvusOk) {
            writeMilvus(document, chunks, vectors, generation);
        } else {
            log.warn("Milvus 不可用，跳过向量写入 documentId={}", document.getId());
        }
        return generation;
    }

    public void deleteByDocument(Long documentId) {
        if (documentId == null) {
            return;
        }
        if (ensureEsIndex()) {
            try {
                es.post().uri("/" + indexName + "/_delete_by_query?refresh=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("query", Map.of("term", Map.of("documentId", documentId))))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException ex) {
                log.warn("清理 ES 文档索引失败 documentId={}：{}", documentId, ex.getMessage());
            }
        }
        if (ensureCollection()) {
            try {
                milvusDelete("documentId == " + documentId);
            } catch (RuntimeException ex) {
                log.warn("清理 Milvus 文档向量失败 documentId={}：{}", documentId, ex.getMessage());
            }
        }
    }

    public void deleteByKb(Long kbId) {
        if (kbId == null) {
            return;
        }
        if (ensureEsIndex()) {
            try {
                es.post().uri("/" + indexName + "/_delete_by_query?refresh=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("query", Map.of("term", Map.of("kbId", kbId))))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException ex) {
                log.warn("清理 ES 知识库索引失败 kbId={}：{}", kbId, ex.getMessage());
            }
        }
        if (ensureCollection()) {
            try {
                milvusDelete("kbId == " + kbId);
            } catch (RuntimeException ex) {
                log.warn("清理 Milvus 知识库向量失败 kbId={}：{}", kbId, ex.getMessage());
            }
        }
    }

    private void writeEs(KbDocument document, List<KbChunk> chunks, List<float[]> vectors, String generation,
                         KbKind kind, List<KbQuestion> questions) {
        Map<Long, KbQuestion> questionByChunk = new HashMap<>();
        for (KbQuestion question : questions) {
            if (question.chunkId() != null) {
                questionByChunk.putIfAbsent(question.chunkId(), question);
            }
        }
        StringBuilder body = new StringBuilder();
        try {
            for (int i = 0; i < chunks.size(); i++) {
                KbChunk chunk = chunks.get(i);
                body.append(mapper.writeValueAsString(Map.of("index",
                        Map.of("_index", indexName, "_id", chunk.chunkKey())))).append('\n');
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("kbId", document.kbId());
                source.put("documentId", document.getId());
                source.put("ordinal", chunk.ordinal());
                source.put("kind", kind == null ? KbKind.TECH_DOC.name() : kind.name());
                if (chunk.heading() != null) {
                    source.put("heading", chunk.heading());
                }
                if (chunk.pageNo() != null) {
                    source.put("pageNo", chunk.pageNo());
                }
                source.put("indexGeneration", generation);
                source.put("text", chunk.text());
                KbQuestion question = questionByChunk.get(chunk.getId());
                if (question != null) {
                    source.put("questionId", question.getId());
                    source.put("stem", question.stem());
                    if (question.difficulty() != null) {
                        source.put("difficulty", question.difficulty());
                    }
                    if (question.company() != null) {
                        source.put("company", question.company());
                    }
                    if (question.year() != null) {
                        source.put("year", question.year());
                    }
                    source.put("tags", parseTags(question.tagsJson()));
                }
                body.append(mapper.writeValueAsString(source)).append('\n');
            }
            String response = es.post().uri("/_bulk?refresh=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            JsonNode result = mapper.readTree(response == null ? "{}" : response);
            if (result.path("errors").asBoolean(false)) {
                throw new IllegalStateException("ES bulk 写入存在失败项");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("写入 ES 索引失败：" + ex.getMessage(), ex);
        }
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(tagsJson, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void writeMilvus(KbDocument document, List<KbChunk> chunks, List<float[]> vectors, String generation) {
        for (int start = 0; start < chunks.size(); start += MILVUS_BATCH) {
            int end = Math.min(start + MILVUS_BATCH, chunks.size());
            List<Map<String, Object>> rows = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                KbChunk chunk = chunks.get(i);
                Map<String, Object> row = new HashMap<>();
                row.put("id", chunk.chunkKey());
                row.put("kbId", document.kbId());
                row.put("documentId", document.getId());
                row.put("ordinal", chunk.ordinal());
                row.put("heading", chunk.heading() == null ? "" : truncate(chunk.heading(), 512));
                row.put("pageNo", chunk.pageNo() == null ? 0 : chunk.pageNo());
                row.put("indexGeneration", generation);
                row.put("text", truncate(chunk.text(), MAX_TEXT));
                row.put("vector", toList(vectors.get(i)));
                rows.add(row);
            }
            String response = milvus.post().uri("/v2/vectordb/entities/insert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("collectionName", collection, "data", rows))
                    .retrieve()
                    .body(String.class);
            requireOk(response, "写入 Milvus 失败");
        }
    }

    private void milvusDelete(String filter) {
        String response = milvus.post().uri("/v2/vectordb/entities/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("collectionName", collection, "filter", filter))
                .retrieve()
                .body(String.class);
        requireOk(response, "删除 Milvus 数据失败");
    }

    /** @return true 表示 ES 可用；不可用返回 false 并进入冷却窗口，调用方据此降级而不是抛错。 */
    private boolean ensureEsIndex() {
        if (esReady) {
            return true;
        }
        synchronized (this) {
            if (esReady) {
                return true;
            }
            if (System.currentTimeMillis() < esDownUntil) {
                return false;
            }
            try {
                boolean exists = es.head().uri("/" + indexName)
                        .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
                if (!exists) {
                    try {
                        es.put().uri("/" + indexName)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(indexMapping())
                                .retrieve().toBodilessEntity();
                    } catch (RuntimeException ex) {
                        // 并发创建时另一个实例可能刚建好；再探测一次
                        if (!es.head().uri("/" + indexName)
                                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful())) {
                            throw ex;
                        }
                    }
                }
                esReady = true;
                ensureEsMapping();
                return true;
            } catch (RuntimeException ex) {
                esDownUntil = System.currentTimeMillis() + DOWN_COOLDOWN_MS;
                log.warn("ES 不可用（{}），{} 秒内跳过外部索引：{}", indexName, DOWN_COOLDOWN_MS / 1000, ex.getMessage());
                return false;
            }
        }
    }

    /** 已有索引补字段：ES 允许向 mapping 追加新字段，重启后自动对齐。 */
    private void ensureEsMapping() {
        if (mappingReady) {
            return;
        }
        synchronized (this) {
            if (mappingReady) {
                return;
            }
            try {
                es.put().uri("/" + indexName + "/_mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("properties", mappingProperties()))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException ex) {
                log.warn("更新 ES mapping 失败，题库字段可能不可过滤：{}", ex.getMessage());
            }
            mappingReady = true;
        }
    }

    private Map<String, Object> indexMapping() {
        return Map.of(
                "settings", Map.of("analysis", Map.of("analyzer", Map.of(
                        "panel_smartcn", Map.of("type", "custom", "tokenizer", "smartcn_tokenizer")))),
                "mappings", Map.of("properties", mappingProperties()));
    }

    private Map<String, Object> mappingProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kbId", Map.of("type", "long"));
        properties.put("documentId", Map.of("type", "long"));
        properties.put("ordinal", Map.of("type", "integer"));
        properties.put("kind", Map.of("type", "keyword"));
        properties.put("heading", Map.of("type", "keyword"));
        properties.put("pageNo", Map.of("type", "integer"));
        properties.put("indexGeneration", Map.of("type", "keyword"));
        properties.put("text", Map.of("type", "text", "analyzer", "panel_smartcn"));
        properties.put("questionId", Map.of("type", "long"));
        properties.put("stem", Map.of("type", "text", "analyzer", "panel_smartcn"));
        properties.put("difficulty", Map.of("type", "keyword"));
        properties.put("company", Map.of("type", "keyword"));
        properties.put("year", Map.of("type", "integer"));
        properties.put("tags", Map.of("type", "keyword"));
        return properties;
    }

    /** @return true 表示 Milvus 可用；不可用返回 false 并进入冷却窗口。 */
    private boolean ensureCollection() {
        if (collectionReady) {
            return true;
        }
        synchronized (this) {
            if (collectionReady) {
                return true;
            }
            if (System.currentTimeMillis() < milvusDownUntil) {
                return false;
            }
            try {
                String raw = milvus.post().uri("/v2/vectordb/collections/has")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("collectionName", collection))
                        .retrieve().body(String.class);
                if (!parseExists(raw)) {
                    String created = milvus.post().uri("/v2/vectordb/collections/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(collectionBody())
                            .retrieve().body(String.class);
                    requireOk(created, "创建 Milvus collection 失败");
                }
                collectionReady = true;
                return true;
            } catch (RuntimeException ex) {
                milvusDownUntil = System.currentTimeMillis() + DOWN_COOLDOWN_MS;
                log.warn("Milvus 不可用（{}），{} 秒内跳过向量写入：{}",
                        collection, DOWN_COOLDOWN_MS / 1000, ex.getMessage());
                return false;
            }
        }
    }

    private Map<String, Object> collectionBody() {
        List<Map<String, Object>> fields = List.of(
                field("id", "VarChar", true, Map.of("max_length", "128")),
                field("kbId", "Int64", false, Map.of()),
                field("documentId", "Int64", false, Map.of()),
                field("ordinal", "Int64", false, Map.of()),
                field("heading", "VarChar", false, Map.of("max_length", "512")),
                field("pageNo", "Int64", false, Map.of()),
                field("indexGeneration", "VarChar", false, Map.of("max_length", "128")),
                field("text", "VarChar", false, Map.of("max_length", "65535")),
                field("vector", "FloatVector", false, Map.of("dim", String.valueOf(VECTOR_DIMENSIONS))));
        return Map.of("collectionName", collection,
                "schema", Map.of("autoId", false, "enableDynamicField", false, "fields", fields),
                "indexParams", List.of(Map.of("fieldName", "vector", "indexName", "vector_index",
                        "indexType", "HNSW", "metricType", "COSINE",
                        "params", Map.of("M", 16, "efConstruction", 200))));
    }

    private static Map<String, Object> field(String name, String type, boolean primary, Map<String, String> params) {
        return Map.of("fieldName", name, "dataType", type, "isPrimary", primary, "elementTypeParams", params);
    }

    private static boolean parseExists(String raw) {
        try {
            return new ObjectMapper().readTree(raw == null ? "{}" : raw).path("data").path("has").asBoolean(false);
        } catch (Exception ex) {
            return false;
        }
    }

    private void requireOk(String raw, String message) {
        try {
            JsonNode node = mapper.readTree(raw == null ? "{}" : raw);
            if (node.path("code").asInt(0) != 0) {
                throw new IllegalStateException(message + "：" + node.path("message").asText("unknown"));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(message + "：" + ex.getMessage(), ex);
        }
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
