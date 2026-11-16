package com.panel.knowledge.infrastructure.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.knowledge.application.KbChunkRepository;
import com.panel.knowledge.application.KbGraphSettings;
import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbConcept;
import com.panel.knowledge.infrastructure.persistence.KbConceptStore;
import com.panel.knowledge.infrastructure.persistence.KbGraphStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图谱召回：问题 → 实体链接 → 1~2 跳邻域 → 提及证据块。
 *
 * <p>只在 {@code panel.kb.graph.enabled=true} 且库里有图数据时产生候选；否则返回空，检索行为与原来一致。</p>
 */
@Component
public class KbGraphRetriever {

    public record LinkedConcept(Long id, String name) {
    }

    public record Expansion(List<LinkedConcept> seeds, int neighbors, List<KbRetriever.ScoredChunk> hits) {
        public boolean empty() {
            return hits.isEmpty();
        }
    }

    private static final long CACHE_TTL_MS = 60_000L;
    private static final int MAX_SEEDS = 5;

    private final KbConceptStore concepts;
    private final KbGraphStore graph;
    private final KbChunkRepository chunks;
    private final KbGraphSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public KbGraphRetriever(KbConceptStore concepts, KbGraphStore graph,
                            KbChunkRepository chunks, KbGraphSettings settings) {
        this.concepts = concepts;
        this.graph = graph;
        this.chunks = chunks;
        this.settings = settings;
    }

    public Expansion expand(String query, Long kbId, Collection<Long> documentIds, int limit) {
        if (!settings.enabled() || query == null || query.isBlank()) {
            return new Expansion(List.of(), 0, List.of());
        }
        List<LinkedConcept> seeds = link(kbId, query);
        if (seeds.isEmpty()) {
            return new Expansion(List.of(), 0, List.of());
        }
        List<Long> seedIds = seeds.stream().map(LinkedConcept::id).toList();
        Map<Long, Double> weights = graph.neighbors(kbId, seedIds, settings.maxHops(),
                settings.relationTypes(), settings.maxNeighbors());
        List<Long> chunkIds = graph.chunksByConcepts(kbId, weights.keySet(), settings.maxChunks());
        if (chunkIds.isEmpty()) {
            return new Expansion(seeds, weights.size(), List.of());
        }
        Map<Long, KbChunk> byId = new HashMap<>();
        for (KbChunk chunk : chunks.findByIds(chunkIds)) {
            byId.put(chunk.getId(), chunk);
        }
        List<KbRetriever.ScoredChunk> hits = new ArrayList<>();
        int rank = 0;
        for (Long chunkId : chunkIds) {
            KbChunk chunk = byId.get(chunkId);
            if (chunk == null || !documentIds.contains(chunk.documentId())) {
                continue;
            }
            rank++;
            hits.add(new KbRetriever.ScoredChunk(
                    chunk.chunkKey(),
                    chunk.documentId(),
                    chunk.ordinal(),
                    chunk.heading(),
                    chunk.pageNo(),
                    null,
                    chunk.text(),
                    1.0 / rank,
                    rank));
            if (hits.size() >= limit) {
                break;
            }
        }
        return new Expansion(seeds, weights.size(), hits);
    }

    /** 实体链接：用库内概念名/别名做包含匹配，长名优先（“RDB 持久化”优先于“持久化”）。 */
    public List<LinkedConcept> link(Long kbId, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<KbConcept> candidates = conceptsOf(kbId);
        List<LinkedConcept> matched = new ArrayList<>();
        for (KbConcept concept : candidates) {
            String name = concept.normalizedName();
            if (name.length() < 2) {
                continue;
            }
            if (q.contains(name) || aliases(concept).stream().anyMatch(q::contains)) {
                matched.add(new LinkedConcept(concept.getId(), concept.canonicalName()));
            }
        }
        matched.sort((a, b) -> Integer.compare(b.name().length(), a.name().length()));
        return matched.size() <= MAX_SEEDS ? matched : matched.subList(0, MAX_SEEDS);
    }

    private List<KbConcept> conceptsOf(Long kbId) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(kbId);
        if (entry != null && now - entry.at() < CACHE_TTL_MS) {
            return entry.concepts();
        }
        List<KbConcept> loaded = concepts.findByKb(kbId, 5000);
        cache.put(kbId, new CacheEntry(now, loaded));
        return loaded;
    }

    private List<String> aliases(KbConcept concept) {
        String json = concept.aliasesJson();
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            List<String> values = mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            List<String> normalized = new ArrayList<>(values.size());
            for (String value : values) {
                normalized.add(KbConcept.normalize(value));
            }
            return normalized;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private record CacheEntry(long at, List<KbConcept> concepts) {
    }
}
