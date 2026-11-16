package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbSkillState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 能力值读写：一条 = user × kb × 知识点。 */
@Repository
public class KbSkillStateStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbSkillState save(KbSkillState state) {
        if (state.getId() == null) {
            em.persist(state);
            em.flush();
            return state;
        }
        return em.merge(state);
    }

    @Transactional(readOnly = true)
    public KbSkillState find(Long userId, Long kbId, Long categoryId) {
        List<KbSkillState> found = em.createQuery("select s from KbSkillState s where s.userId = :userId " +
                        "and s.kbId = :kbId and s.categoryId = :categoryId", KbSkillState.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .setParameter("categoryId", categoryId)
                .setMaxResults(1)
                .getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    @Transactional(readOnly = true)
    public List<KbSkillState> findByKb(Long userId, Long kbId) {
        return em.createQuery("select s from KbSkillState s where s.userId = :userId and s.kbId = :kbId " +
                        "order by s.theta asc", KbSkillState.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    @Transactional
    public void deleteByKb(Long kbId) {
        em.createQuery("delete from KbSkillState s where s.kbId = :kbId")
                .setParameter("kbId", kbId)
                .executeUpdate();
    }
}
