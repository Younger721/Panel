package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbQuestion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** 题库读写：题目按文档整体替换，保证重试幂等。 */
@Repository
public class KbQuestionStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void replaceByDocument(Long documentId, List<KbQuestion> questions) {
        em.createQuery("delete from KbQuestion q where q.documentId = :documentId")
                .setParameter("documentId", documentId)
                .executeUpdate();
        for (KbQuestion question : questions) {
            em.persist(question);
        }
        em.flush();
    }

    @Transactional
    public void deleteByDocument(Long documentId) {
        em.createQuery("delete from KbQuestion q where q.documentId = :documentId")
                .setParameter("documentId", documentId)
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<KbQuestion> findByDocumentId(Long documentId) {
        return em.createQuery("select q from KbQuestion q where q.documentId = :documentId order by q.id",
                        KbQuestion.class)
                .setParameter("documentId", documentId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbQuestion> findByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select q from KbQuestion q where q.chunkId in :chunkIds", KbQuestion.class)
                .setParameter("chunkIds", chunkIds)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbQuestion> findByKb(Long kbId, int limit, int offset) {        return em.createQuery("select q from KbQuestion q where q.kbId = :kbId order by q.id desc",
                        KbQuestion.class)
                .setParameter("kbId", kbId)
                .setFirstResult(Math.max(0, offset))
                .setMaxResults(Math.max(1, Math.min(200, limit)))
                .getResultList();
    }

    /** 抽题用：整个题库读进内存再分层；单库上限 2 万题，超出请先拆库。 */
    @Transactional(readOnly = true)
    public List<KbQuestion> findAllByKb(Long kbId) {
        return em.createQuery("select q from KbQuestion q where q.kbId = :kbId order by q.id asc",
                        KbQuestion.class)
                .setParameter("kbId", kbId)
                .setMaxResults(20_000)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public long countByKb(Long kbId) {
        Long count = em.createQuery("select count(q) from KbQuestion q where q.kbId = :kbId", Long.class)
                .setParameter("kbId", kbId)
                .getSingleResult();
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public List<Long> findIdsByKb(Long kbId) {
        return em.createQuery("select q.id from KbQuestion q where q.kbId = :kbId order by q.id desc", Long.class)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbQuestion> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select q from KbQuestion q where q.id in :ids order by q.id desc",
                        KbQuestion.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public KbQuestion findById(Long id) {
        return em.find(KbQuestion.class, id);
    }
}
