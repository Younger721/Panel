package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbItemCategory;
import com.panel.knowledge.domain.model.KbItemType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 条目归属读写；计数与“未分类”都从这里推导。 */
@Repository
public class KbItemCategoryStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbItemCategory insert(KbItemCategory item) {
        em.persist(item);
        em.flush();
        return item;
    }

    @Transactional
    public void update(KbItemCategory item) {
        em.merge(item);
    }

    @Transactional(readOnly = true)
    public List<KbItemCategory> findByItem(Long kbId, KbItemType type, Long itemId) {
        return em.createQuery("select i from KbItemCategory i where i.kbId = :kbId " +
                        "and i.itemType = :type and i.itemId = :itemId order by i.id asc", KbItemCategory.class)
                .setParameter("kbId", kbId)
                .setParameter("type", type)
                .setParameter("itemId", itemId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbItemCategory> findByCategoryIds(Long kbId, Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select i from KbItemCategory i where i.kbId = :kbId " +
                        "and i.categoryId in :ids order by i.categoryId asc, i.itemId asc", KbItemCategory.class)
                .setParameter("kbId", kbId)
                .setParameter("ids", categoryIds)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbItemCategory> findAllByKb(Long kbId) {
        return em.createQuery("select i from KbItemCategory i where i.kbId = :kbId", KbItemCategory.class)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    /** 一次把一页条目的挂载捞出来，避免卡片渲染时逐条查询。 */
    @Transactional(readOnly = true)
    public List<KbItemCategory> findByItemIds(Long kbId, KbItemType type, Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select i from KbItemCategory i where i.kbId = :kbId " +
                        "and i.itemType = :type and i.itemId in :ids order by i.id asc", KbItemCategory.class)
                .setParameter("kbId", kbId)
                .setParameter("type", type)
                .setParameter("ids", itemIds)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<Long> findItemIdsByCategory(Long kbId, KbItemType type, Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select distinct i.itemId from KbItemCategory i where i.kbId = :kbId " +
                        "and i.itemType = :type and i.categoryId in :ids", Long.class)
                .setParameter("kbId", kbId)
                .setParameter("type", type)
                .setParameter("ids", categoryIds)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<Long> findAssignedItemIds(Long kbId, KbItemType type) {
        return em.createQuery("select distinct i.itemId from KbItemCategory i where i.kbId = :kbId " +
                        "and i.itemType = :type", Long.class)
                .setParameter("kbId", kbId)
                .setParameter("type", type)
                .getResultList();
    }

    /**
     * 把待写入的挂载刷到库里。
     *
     * <p>归类结束后要立刻拍快照算"这次挂了几条"，但 {@link #findAssignedItemIds} 与
     * {@link #snapshotLocked} 都是只读事务，只读事务不会 flush，刚 persist 的行读不到——
     * 报告就会少算一半（实测 12 条只报 4 条）。</p>
     */
    @Transactional
    public void flushPending() {
        em.flush();
    }

    /**
     * 快照：itemId → 是否已人工确认。
     *
     * <p>归类报告按"跑之前 / 跑之后"两次快照的差集来算数，这样数的是**落库结果**，
     * 不会因为分批重试把同一条算成两次。</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, Boolean> snapshotLocked(Long kbId, KbItemType type) {
        List<Object[]> rows = em.createQuery("select i.itemId, i.locked from KbItemCategory i "
                        + "where i.kbId = :kbId and i.itemType = :type", Object[].class)
                .setParameter("kbId", kbId)
                .setParameter("type", type)
                .getResultList();
        Map<Long, Boolean> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((Long) row[0], Boolean.TRUE.equals(row[1]));
        }
        return result;
    }

    @Transactional
    public void clearPrimary(Long kbId, KbItemType type, Long itemId) {
        em.createQuery("update KbItemCategory i set i.primaryFlag = false where i.kbId = :kbId " +
                        "and i.itemType = :type and i.itemId = :itemId")
                .setParameter("kbId", kbId)
                .setParameter("type", type)
                .setParameter("itemId", itemId)
                .executeUpdate();
    }

    @Transactional
    public void delete(KbItemCategory item) {
        em.remove(em.contains(item) ? item : em.merge(item));
    }

    @Transactional
    public int deleteByCategoryId(Long categoryId) {
        return em.createQuery("delete from KbItemCategory i where i.categoryId = :categoryId")
                .setParameter("categoryId", categoryId)
                .executeUpdate();
    }

    /** 级联删目录时用：把整棵子树上的挂载一次性摘掉（题目/切片本身不动，落在"未分类"）。 */
    @Transactional
    public int deleteByCategoryIds(Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return 0;
        }
        return em.createQuery("delete from KbItemCategory i where i.categoryId in :ids")
                .setParameter("ids", categoryIds)
                .executeUpdate();
    }

    @Transactional
    public int deleteByItems(KbItemType type, Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        return em.createQuery("delete from KbItemCategory i where i.itemType = :type and i.itemId in :ids")
                .setParameter("type", type)
                .setParameter("ids", itemIds)
                .executeUpdate();
    }

    @Transactional
    public int deleteByKb(Long kbId) {
        return em.createQuery("delete from KbItemCategory i where i.kbId = :kbId")
                .setParameter("kbId", kbId)
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public boolean existsByCategoryId(Long categoryId) {
        Long count = em.createQuery("select count(i) from KbItemCategory i where i.categoryId = :categoryId",
                        Long.class)
                .setParameter("categoryId", categoryId)
                .getSingleResult();
        return count != null && count > 0;
    }
}
