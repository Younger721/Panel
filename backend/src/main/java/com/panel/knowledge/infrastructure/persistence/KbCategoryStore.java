package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbCategory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 目录树读写：path 用 id 前缀，子树查询直接 like。 */
@Repository
public class KbCategoryStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbCategory insert(KbCategory category) {
        em.persist(category);
        em.flush();
        return category;
    }

    @Transactional
    public KbCategory update(KbCategory category) {
        return em.merge(category);
    }

    @Transactional
    public void delete(KbCategory category) {
        em.remove(em.contains(category) ? category : em.merge(category));
    }

    @Transactional
    public int deleteByKb(Long kbId) {
        return em.createQuery("delete from KbCategory c where c.kbId = :kbId")
                .setParameter("kbId", kbId)
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public KbCategory findById(Long id) {
        return id == null ? null : em.find(KbCategory.class, id);
    }

    @Transactional(readOnly = true)
    public List<KbCategory> findByKb(Long kbId) {
        return em.createQuery("select c from KbCategory c where c.kbId = :kbId " +
                        "order by c.depth asc, c.sortNo asc, c.id asc", KbCategory.class)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbCategory> findSubtree(Long kbId, String pathPrefix) {
        return em.createQuery("select c from KbCategory c where c.kbId = :kbId and c.path like :prefix " +
                        "order by c.depth asc, c.id asc", KbCategory.class)
                .setParameter("kbId", kbId)
                .setParameter("prefix", pathPrefix + "%")
                .getResultList();
    }

    @Transactional(readOnly = true)
    public boolean existsSibling(Long kbId, Long parentId, String name, Long excludeId) {
        Long count = em.createQuery("select count(c) from KbCategory c where c.kbId = :kbId " +
                        "and c.parentId = :parentId and c.name = :name and (:excludeId is null or c.id <> :excludeId)",
                        Long.class)
                .setParameter("kbId", kbId)
                .setParameter("parentId", parentId == null ? KbCategory.ROOT_PARENT_ID : parentId)
                .setParameter("name", name)
                .setParameter("excludeId", excludeId)
                .getSingleResult();
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean existsPathKey(Long kbId, String pathKey, Long excludeId) {
        Long count = em.createQuery("select count(c) from KbCategory c where c.kbId = :kbId " +
                        "and c.pathKey = :pathKey and (:excludeId is null or c.id <> :excludeId)", Long.class)
                .setParameter("kbId", kbId)
                .setParameter("pathKey", pathKey)
                .setParameter("excludeId", excludeId)
                .getSingleResult();
        return count != null && count > 0;
    }
}
