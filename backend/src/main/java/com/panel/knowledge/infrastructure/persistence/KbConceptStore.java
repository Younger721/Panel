package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbConcept;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** 概念词表读写：按归一化名去重，保证两个库引用同一条概念。 */
@Repository
public class KbConceptStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbConcept findOrCreate(String name, String type, String description, String aliasesJson) {
        String normalized = KbConcept.normalize(name);
        if (normalized.isBlank()) {
            return null;
        }
        List<KbConcept> found = em.createQuery(
                        "select c from KbConcept c where c.normalizedName = :normalized", KbConcept.class)
                .setParameter("normalized", normalized)
                .setMaxResults(1)
                .getResultList();
        if (!found.isEmpty()) {
            KbConcept existing = found.get(0);
            existing.merge(description, null);
            return existing;
        }
        KbConcept concept = KbConcept.create(name, type, description, aliasesJson);
        em.persist(concept);
        em.flush();
        return concept;
    }

    @Transactional(readOnly = true)
    public List<KbConcept> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select c from KbConcept c where c.id in :ids", KbConcept.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    /** 某个库出现过的概念（用于实体链接的候选集）。 */
    @Transactional(readOnly = true)
    public List<KbConcept> findByKb(Long kbId, int limit) {
        @SuppressWarnings("unchecked")
        List<KbConcept> rows = em.createNativeQuery(
                        "select distinct c.* from kb_concept c "
                                + "join kb_graph_mention m on m.concept_id = c.id "
                                + "where m.kb_id = :kbId limit :limit", KbConcept.class)
                .setParameter("kbId", kbId)
                .setParameter("limit", Math.max(1, limit))
                .getResultList();
        return rows;
    }

    @Transactional(readOnly = true)
    public long countByKb(Long kbId) {
        Number count = (Number) em.createNativeQuery(
                        "select count(distinct m.concept_id) from kb_graph_mention m where m.kb_id = :kbId")
                .setParameter("kbId", kbId)
                .getSingleResult();
        return count == null ? 0 : count.longValue();
    }
}
