package com.panel.knowledge.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * K2 召回：同一份 {@code kbId} 过滤在 ES 与 Milvus 两端都做，避免“只在 DB 层兜”。
 *
 * <p>只负责取候选，不融合、不精排；权限/发布状态由 {@code KbSearchService} 先算成文档白名单。</p>
 */
@Component
public class KbRetriever {

    public record ScoredChunk(
            String chunkKey,
            Long documentId,
            Integer ordinal,
            String heading,
            Integer pageNo,
            String indexGeneration,
            String text,
            double score,
            int rank) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient es;
    private final RestClient milvus;
    private final String indexName;
    private final String collection;

    public KbRetriever() {
        this.es = RestClient.builder().baseUrl(env("PANEL_ES_URL", "http://localhost:9202")).build();
        this.milvus = RestClient.builder().baseUrl(env("PANEL_MILVUS_URL", "http://localhost:19530")).build();
        this.indexName = env("PANEL_KB_ES_INDEX", "panel-knowledge");
        this.collection = env("PANEL_KB_MILVUS_COLLECTION", "panel_knowledge");
    }

    public List<ScoredChunk> keyword(String query, Long kbId, Collection<Long> documentIds, int limit) {
        return keyword(query, kbId, documentIds, limit, KbSearchFilters.none());
    }

    /**
     * 关键词召回：题干（stem）权重高于正文，题库按关键词找题更准。
     * 元数据过滤放在 filter 里，不参与打分，只做前置筛选。
     */
    public List<ScoredChunk> keyword(String query, Long kbId, Collection<Long> documentIds, int limit,
                                     KbSearchFilters filters) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> bool = new java.util.LinkedHashMap<>();
        bool.put("must", List.of(Map.of("bool", Map.of(
                "should", List.of(
                        Map.of("match", Map.of("text", query)),
                        Map.of("match", Map.of("stem", Map.of("query", query, "boost", 3)))),
                "minimum_should_match", 1))));
        List<Map<String, Object>> filter = new ArrayList<>();
        filter.add(Map.of("term", Map.of("kbId", kbId)));
        filter.add(Map.of("terms", Map.of("documentId", documentIds)));
        if (filters != null && !filters.isEmpty()) {
            if (filters.company() != null && !filters.company().isBlank()) {
                filter.add(Map.of("term", Map.of("company", filters.company())));
            }
            if (filters.difficulty() != null && !filters.difficulty().isBlank()) {
                filter.add(Map.of("term", Map.of("difficulty", filters.difficulty())));
            }
            if (filters.year() != null) {
                filter.add(Map.of("term", Map.of("year", filters.year())));
            }
            if (filters.tags() != null && !filters.tags().isEmpty()) {
                filter.add(Map.of("terms", Map.of("tags", filters.tags())));
            }
        }
        bool.put("filter", filter);
        Map<String, Object> body = Map.of(
                "query", Map.of("bool", bool),
                "size", Math.max(1, limit),
                "_source", List.of("documentId", "ordinal", "heading", "pageNo", "indexGeneration", "text"));
        String raw = es.post().uri("/" + indexName + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        List<ScoredChunk> hits = new ArrayList<>();
        try {
            JsonNode rows = mapper.readTree(raw == null ? "{}" : raw).path("hits").path("hits");
            int rank = 0;
            for (JsonNode row : rows) {
                JsonNode source = row.path("_source");
                rank++;
                hits.add(new ScoredChunk(
                        row.path("_id").asText(),
                        source.path("documentId").asLong(),
                        source.path("ordinal").asInt(0),
                        source.hasNonNull("heading") ? source.path("heading").asText() : null,
                        source.hasNonNull("pageNo") ? source.path("pageNo").asInt() : null,
                        source.hasNonNull("indexGeneration") ? source.path("indexGeneration").asText() : null,
                        source.path("text").asText(""),
                        row.path("_score").asDouble(0),
                        rank));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("解析 ES 检索结果失败：" + ex.getMessage(), ex);
        }
        return hits;
    }

    public List<ScoredChunk> vector(float[] vector, Long kbId, Collection<Long> documentIds, int limit) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        String filter = "kbId == " + kbId + " and documentId in " + documentIds;
        Map<String, Object> body = Map.of(
                "collectionName", collection,
                "data", List.of(toList(vector)),
                "annsField", "vector",
                "filter", filter,
                "limit", Math.max(1, limit),
                "outputFields", List.of("id", "documentId", "ordinal", "heading", "pageNo",
                        "indexGeneration", "text"));
        String raw = milvus.post().uri("/v2/vectordb/entities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        List<ScoredChunk> hits = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(raw == null ? "{}" : raw);
            if (root.path("code").asInt(0) != 0) {
                throw new IllegalStateException("Milvus 检索失败：" + root.path("message").asText("unknown"));
            }
            int rank = 0;
            for (JsonNode row : root.path("data")) {
                rank++;
                hits.add(new ScoredChunk(
                        row.path("id").asText(),
                        row.path("documentId").asLong(),
                        row.path("ordinal").asInt(0),
                        row.hasNonNull("heading") ? row.path("heading").asText() : null,
                        row.hasNonNull("pageNo") ? row.path("pageNo").asInt() : null,
                        row.hasNonNull("indexGeneration") ? row.path("indexGeneration").asText() : null,
                        row.path("text").asText(""),
                        row.path("distance").asDouble(0),
                        rank));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("解析 Milvus 检索结果失败：" + ex.getMessage(), ex);
        }
        return hits;
    }

    /**
     * 元数据过滤的兜底：向量通道没有题库字段，融合后按 chunkKey 回查 ES，把不匹配的候选剔掉。
     */
    public java.util.Set<String> filterChunkKeys(Long kbId, Collection<String> chunkKeys, KbSearchFilters filters) {
        if (chunkKeys == null || chunkKeys.isEmpty() || filters == null || filters.isEmpty()) {
            return java.util.Set.copyOf(chunkKeys == null ? List.of() : chunkKeys);
        }
        List<Map<String, Object>> filter = new ArrayList<>();
        filter.add(Map.of("term", Map.of("kbId", kbId)));
        if (filters.company() != null && !filters.company().isBlank()) {
            filter.add(Map.of("term", Map.of("company", filters.company())));
        }
        if (filters.difficulty() != null && !filters.difficulty().isBlank()) {
            filter.add(Map.of("term", Map.of("difficulty", filters.difficulty())));
        }
        if (filters.year() != null) {
            filter.add(Map.of("term", Map.of("year", filters.year())));
        }
        if (filters.tags() != null && !filters.tags().isEmpty()) {
            filter.add(Map.of("terms", Map.of("tags", filters.tags())));
        }
        Map<String, Object> body = Map.of(
                "query", Map.of("bool", Map.of(
                        "must", List.of(Map.of("ids", Map.of("values", List.copyOf(chunkKeys)))),
                        "filter", filter)),
                "size", chunkKeys.size(),
                "_source", false);
        String raw = es.post().uri("/" + indexName + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        try {
            JsonNode rows = mapper.readTree(raw == null ? "{}" : raw).path("hits").path("hits");
            for (JsonNode row : rows) {
                allowed.add(row.path("_id").asText());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("解析 ES 过滤结果失败：" + ex.getMessage(), ex);
        }
        return allowed;
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
