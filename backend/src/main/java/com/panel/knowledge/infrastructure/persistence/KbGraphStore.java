package com.panel.knowledge.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图数据读写：边 + 提及（证据）。
 *
 * <p>图规模在百万边以内，邻域查询直接在 MySQL 里做；将来换 Neo4j 只需替换这个类的实现。</p>
 */
@Repository
public class KbGraphStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void addEdge(Long kbId, Long sourceId, Long targetId, String type, double weight,
                        Long evidenceChunkId, Long documentId, String extractorVersion) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            return;
        }
        em.createNativeQuery("insert into kb_graph_edge "
                        + "(kb_id, source_concept_id, target_concept_id, type, weight, "
                        + "evidence_chunk_id, document_id, extractor_version, created_at) "
                        + "values (:kb, :source, :target, :type, :weight, :evidence, :document, :version, now(6)) "
                        + "on duplicate key update "
                        + "weight = greatest(weight, values(weight)), "
                        + "evidence_chunk_id = ifnull(evidence_chunk_id, values(evidence_chunk_id))")
                .setParameter("kb", kbId)
                .setParameter("source", sourceId)
                .setParameter("target", targetId)
                .setParameter("type", type)
                .setParameter("weight", weight)
                .setParameter("evidence", evidenceChunkId)
                .setParameter("document", documentId)
                .setParameter("version", extractorVersion)
                .executeUpdate();
    }

    @Transactional
    public void addMention(Long conceptId, Long chunkId, Long kbId, Integer start, Integer end, String quote) {
        if (conceptId == null || chunkId == null) {
            return;
        }
        em.createNativeQuery("insert ignore into kb_graph_mention "
                        + "(concept_id, chunk_id, kb_id, start_offset, end_offset, quote) "
                        + "values (:concept, :chunk, :kb, :start, :end, :quote)")
                .setParameter("concept", conceptId)
                .setParameter("chunk", chunkId)
                .setParameter("kb", kbId)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("quote", quote)
                .executeUpdate();
    }

    @Transactional
    public void deleteByDocument(Long documentId) {
        em.createNativeQuery("delete from kb_graph_edge where document_id = :documentId")
                .setParameter("documentId", documentId)
                .executeUpdate();
        em.createNativeQuery("delete m from kb_graph_mention m "
                        + "join kb_chunk c on c.id = m.chunk_id where c.document_id = :documentId")
                .setParameter("documentId", documentId)
                .executeUpdate();
    }

    /**
     * 从种子概念出发的邻域（BFS），返回 conceptId → 累计权重。
     *
     * <p>权重按边权 × 关系类型系数 × 0.7^depth 衰减，避免两跳把弱关系放大。</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> neighbors(Long kbId, Collection<Long> seedIds, int hops,
                                       Collection<String> types, int maxPerLevel) {
        if (seedIds == null || seedIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> weights = new LinkedHashMap<>();
        for (Long id : seedIds) {
            weights.put(id, 1.0);
        }
        List<Long> frontier = new ArrayList<>(seedIds);
        for (int depth = 1; depth <= Math.max(1, hops); depth++) {
            if (frontier.isEmpty() || weights.size() >= maxPerLevel) {
                break;
            }
            List<Object[]> rows = edgesOf(kbId, frontier, types);
            Map<Long, Double> next = new HashMap<>();
            for (Object[] row : rows) {
                Long source = ((Number) row[0]).longValue();
                Long target = ((Number) row[1]).longValue();
                double edgeWeight = ((Number) row[2]).doubleValue();
                String type = (String) row[3];
                Long other = frontier.contains(source) ? target : source;
                double value = edgeWeight * typeWeight(type) * Math.pow(0.7, depth);
                next.merge(other, value, Math::max);
            }
            frontier = new ArrayList<>();
            for (Map.Entry<Long, Double> entry : next.entrySet()) {
                if (weights.containsKey(entry.getKey())) {
                    continue;
                }
                weights.put(entry.getKey(), entry.getValue());
                frontier.add(entry.getKey());
                if (weights.size() >= maxPerLevel) {
                    break;
                }
            }
            frontier.sort((a, b) -> Double.compare(weights.getOrDefault(b, 0d), weights.getOrDefault(a, 0d)));
        }
        return weights;
    }

    private List<Object[]> edgesOf(Long kbId, List<Long> ids, Collection<String> types) {
        String typeClause = "";
        Map<String, Object> params = new HashMap<>();
        if (types != null && !types.isEmpty()) {
            typeClause = " and type in (:types)";
            params.put("types", types);
        }
        @SuppressWarnings("unchecked")
        jakarta.persistence.Query query = em.createNativeQuery(
                        "select source_concept_id, target_concept_id, weight, type from kb_graph_edge "
                                + "where kb_id = :kbId and (source_concept_id in (:ids) or target_concept_id in (:ids))"
                                + typeClause)
                .setParameter("kbId", kbId)
                .setParameter("ids", ids);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    private static double typeWeight(String type) {
        if (type == null) {
            return 0.6;
        }
        return switch (type) {
            case "CONTRASTS_WITH", "CAUSES", "PREREQUISITE" -> 1.0;
            case "DEPENDS_ON", "PART_OF", "SOLVES" -> 0.8;
            default -> 0.5;
        };
    }

    /** 概念命中的 chunk：按提及时间倒序，取最新的一批。 */
    @Transactional(readOnly = true)
    public List<Long> chunksByConcepts(Long kbId, Collection<Long> conceptIds, int limit) {
        if (conceptIds == null || conceptIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                        "select chunk_id from kb_graph_mention "
                                + "where kb_id = :kbId and concept_id in (:ids) "
                                + "group by chunk_id order by max(id) desc limit :limit")
                .setParameter("kbId", kbId)
                .setParameter("ids", conceptIds)
                .setParameter("limit", Math.max(1, limit))
                .getResultList();
        List<Long> ids = new ArrayList<>(rows.size());
        for (Number row : rows) {
            ids.add(row.longValue());
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public List<Long> conceptsByChunks(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                        "select distinct concept_id from kb_graph_mention where chunk_id in (:ids)")
                .setParameter("ids", chunkIds)
                .getResultList();
        List<Long> ids = new ArrayList<>(rows.size());
        for (Number row : rows) {
            ids.add(row.longValue());
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public long countEdges(Long kbId) {
        Number count = (Number) em.createNativeQuery(
                        "select count(*) from kb_graph_edge where kb_id = :kbId")
                .setParameter("kbId", kbId)
                .getSingleResult();
        return count == null ? 0 : count.longValue();
    }
}
