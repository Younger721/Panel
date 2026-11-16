package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbCategoryRelation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 知识点横切关系读写；按节点取双向 1 跳。 */
@Repository
public class KbCategoryRelationStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbCategoryRelation save(KbCategoryRelation relation) {
        if (relation.getId() == null) {
            em.persist(relation);
            em.flush();
            return relation;
        }
        return em.merge(relation);
    }

    @Transactional(readOnly = true)
    public KbCategoryRelation findById(Long id) {
        return id == null ? null : em.find(KbCategoryRelation.class, id);
    }

    @Transactional(readOnly = true)
    public List<KbCategoryRelation> findByCategory(Long kbId, Long categoryId) {
        return em.createQuery("select r from KbCategoryRelation r where r.kbId = :kbId " +
                        "and (r.fromCategoryId = :categoryId or r.toCategoryId = :categoryId) " +
                        "order by r.weight desc, r.id asc", KbCategoryRelation.class)
                .setParameter("kbId", kbId)
                .setParameter("categoryId", categoryId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbCategoryRelation> findByCategories(Long kbId, List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select r from KbCategoryRelation r where r.kbId = :kbId " +
                        "and (r.fromCategoryId in :ids or r.toCategoryId in :ids) order by r.weight desc",
                        KbCategoryRelation.class)
                .setParameter("kbId", kbId)
                .setParameter("ids", categoryIds)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbCategoryRelation> findByKb(Long kbId) {
        return em.createQuery("select r from KbCategoryRelation r where r.kbId = :kbId order by r.id asc",
                        KbCategoryRelation.class)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    @Transactional
    public void delete(KbCategoryRelation relation) {
        em.remove(em.contains(relation) ? relation : em.merge(relation));
    }

    @Transactional
    public int deleteByCategory(Long categoryId) {
        return em.createQuery("delete from KbCategoryRelation r where r.fromCategoryId = :categoryId " +
                        "or r.toCategoryId = :categoryId")
                .setParameter("categoryId", categoryId)
                .executeUpdate();
    }

    @Transactional
    public int deleteByKb(Long kbId) {
        return em.createQuery("delete from KbCategoryRelation r where r.kbId = :kbId")
                .setParameter("kbId", kbId)
                .executeUpdate();
    }
}
