package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbSkillInterviewImport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 面试信号回填台账：只用来判重和自查，不参与计分。 */
@Repository
public class KbSkillInterviewImportStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbSkillInterviewImport save(KbSkillInterviewImport row) {
        em.persist(row);
        return row;
    }

    /** 已经折算过的 turn id（回填时先减掉，保证幂等）。 */
    @Transactional(readOnly = true)
    public Set<Long> importedTurnIds(Long kbId) {
        List<Long> ids = em.createQuery(
                        "select i.turnId from KbSkillInterviewImport i where i.kbId = :kbId", Long.class)
                .setParameter("kbId", kbId)
                .getResultList();
        return new HashSet<>(ids);
    }

    @Transactional(readOnly = true)
    public long countByKb(Long kbId) {
        return em.createQuery("select count(i) from KbSkillInterviewImport i where i.kbId = :kbId", Long.class)
                .setParameter("kbId", kbId)
                .getSingleResult();
    }

    @Transactional
    public int deleteByKb(Long kbId) {
        return em.createQuery("delete from KbSkillInterviewImport i where i.kbId = :kbId")
                .setParameter("kbId", kbId)
                .executeUpdate();
    }
}
