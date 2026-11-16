package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbChatMessage;
import com.panel.knowledge.domain.model.KbChatRole;
import com.panel.knowledge.domain.model.KbChatSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 问答会话/消息的持久化；问答流式生成跑在虚拟线程上，所以每个方法都是独立事务。 */
@Repository
public class KbChatStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbChatSession createSession(Long kbId, Long userId, String title) {
        KbChatSession session = KbChatSession.create(kbId, userId, title);
        em.persist(session);
        return session;
    }

    @Transactional(readOnly = true)
    public Optional<KbChatSession> findSession(Long sessionId) {
        return Optional.ofNullable(em.find(KbChatSession.class, sessionId));
    }

    @Transactional(readOnly = true)
    public List<KbChatSession> sessions(Long kbId, Long userId) {
        return em.createQuery("""
                        select s from KbChatSession s
                        where s.kbId = :kbId and s.userId = :userId
                        order by s.updatedAt desc, s.id desc
                        """, KbChatSession.class)
                .setParameter("kbId", kbId)
                .setParameter("userId", userId)
                .setMaxResults(50)
                .getResultList();
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        em.createQuery("delete from KbChatMessage m where m.sessionId = :sessionId")
                .setParameter("sessionId", sessionId)
                .executeUpdate();
        findSession(sessionId).ifPresent(em::remove);
    }

    @Transactional
    public KbChatMessage append(Long sessionId, KbChatRole role, String content,
                                String citationsJson, String stagesJson, Integer latencyMs) {
        int sequence = nextSequence(sessionId);
        KbChatMessage message = role == KbChatRole.USER
                ? KbChatMessage.user(sessionId, content, sequence)
                : KbChatMessage.assistant(sessionId, content, citationsJson, stagesJson, latencyMs, sequence);
        em.persist(message);
        findSession(sessionId).ifPresent(KbChatSession::touch);
        return message;
    }

    @Transactional(readOnly = true)
    public List<KbChatMessage> messages(Long sessionId) {
        return em.createQuery("""
                        select m from KbChatMessage m
                        where m.sessionId = :sessionId
                        order by m.sequenceNo asc
                        """, KbChatMessage.class)
                .setParameter("sessionId", sessionId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbChatMessage> recent(Long sessionId, int limit) {
        List<KbChatMessage> latest = em.createQuery("""
                        select m from KbChatMessage m
                        where m.sessionId = :sessionId
                        order by m.sequenceNo desc
                        """, KbChatMessage.class)
                .setParameter("sessionId", sessionId)
                .setMaxResults(Math.max(1, limit))
                .getResultList();
        java.util.Collections.reverse(latest);
        return latest;
    }

    @Transactional
    public void retitle(Long sessionId, String title) {
        findSession(sessionId).ifPresent(session -> session.retitle(title));
    }

    private int nextSequence(Long sessionId) {
        Long max = em.createQuery("select max(m.sequenceNo) from KbChatMessage m where m.sessionId = :sessionId", Long.class)
                .setParameter("sessionId", sessionId)
                .getSingleResult();
        return max == null ? 0 : max.intValue() + 1;
    }
}
