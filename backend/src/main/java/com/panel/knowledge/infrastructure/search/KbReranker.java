package com.panel.knowledge.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.infrastructure.bailian.DashScopeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 百炼原生 rerank 接口（{@code /api/v1/services/rerank/text-rerank/text-rerank}）。
 *
 * <p>兼容模式没有 rerank 端点，旧的 {@code BailianRerankAdapter} 走 {@code /compatible-mode/v1/rerank} 会 404；
 * K2 用原生路径并保留 relevance_score。</p>
 */
@Component
public class KbReranker {

    public record Scored(int index, double score) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;
    private final String model;

    public KbReranker(DashScopeProperties dashScope,
                      @Value("${panel.kb.search.rerank-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
                      String url,
                      @Value("${panel.kb.search.rerank-model:qwen3.7-text-rerank}") String model) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.client = RestClient.builder()
                .baseUrl(url)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + dashScope.apiKeyForDashScopeOnly())
                .build();
        this.model = model;
    }

    public List<Scored> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        String raw = client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", model,
                        "input", Map.of("query", query, "documents", documents),
                        "parameters", Map.of("return_documents", false, "top_n", Math.min(topN, documents.size()))))
                .retrieve()
                .body(String.class);
        try {
            JsonNode results = mapper.readTree(raw == null ? "{}" : raw).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new IllegalStateException("rerank 返回为空");
            }
            List<Scored> scores = new ArrayList<>(results.size());
            for (JsonNode row : results) {
                scores.add(new Scored(row.path("index").asInt(-1), row.path("relevance_score").asDouble(0)));
            }
            return scores;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("解析 rerank 结果失败：" + ex.getMessage(), ex);
        }
    }
}
