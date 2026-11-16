package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KbQuizAttempt;
import com.panel.knowledge.domain.model.KbQuizSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 刷题读写：会话、作答、以及从作答推导出来的统计。
 *
 * <p>统计口径统一放在这里，别散落到 service：「最近一次作答」用 {@code max(id) per question}
 * 取，避免把整条历史拉进内存。</p>
 */
@Repository
public class KbQuizStore {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public KbQuizSession saveSession(KbQuizSession session) {
        if (session.getId() == null) {
            em.persist(session);
            em.flush();
            return session;
        }
        return em.merge(session);
    }

    @Transactional
    public KbQuizAttempt saveAttempt(KbQuizAttempt attempt) {
        if (attempt.getId() == null) {
            em.persist(attempt);
            em.flush();
            return attempt;
        }
        return em.merge(attempt);
    }

    @Transactional
    public void saveAttempts(Collection<KbQuizAttempt> attempts) {
        for (KbQuizAttempt attempt : attempts) {
            em.persist(attempt);
        }
        em.flush();
    }

    @Transactional(readOnly = true)
    public KbQuizSession findSession(Long sessionId) {
        return sessionId == null ? null : em.find(KbQuizSession.class, sessionId);
    }

    @Transactional(readOnly = true)
    public KbQuizAttempt findAttempt(Long attemptId) {
        return attemptId == null ? null : em.find(KbQuizAttempt.class, attemptId);
    }

    @Transactional(readOnly = true)
    public List<KbQuizAttempt> findAttempts(Long sessionId) {
        return em.createQuery("select a from KbQuizAttempt a where a.sessionId = :sessionId order by a.ordinal asc",
                        KbQuizAttempt.class)
                .setParameter("sessionId", sessionId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<KbQuizSession> recentSessions(Long userId, Long kbId, int limit) {
        return em.createQuery("select s from KbQuizSession s where s.userId = :userId and s.kbId = :kbId " +
                        "order by s.startedAt desc", KbQuizSession.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .setMaxResults(Math.max(1, limit))
                .getResultList();
    }

    /**
     * 每道题只取最近一次**已作答**的记录，用于抽题权重（错过的题优先、刚做对过的降权）。
     *
     * <p>必须过滤 {@code result is null}：抽题时会先落一条未作答的记录，如果把它当成"最近一次"，
     * 历史信号就被稀释掉了——打开一组题不作答，下一轮权重会莫名其妙变回"没做过"。</p>
     */
    @Transactional(readOnly = true)
    public List<KbQuizAttempt> findLatestPerQuestion(Long userId, Long kbId) {
        return em.createQuery("select a from KbQuizAttempt a where a.userId = :userId and a.kbId = :kbId " +
                        "and a.result is not null " +
                        "and a.id in (select max(b.id) from KbQuizAttempt b where b.userId = :userId " +
                        "and b.kbId = :kbId and b.result is not null group by b.questionId)",
                KbQuizAttempt.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public long countAnswered(Long userId, Long kbId) {
        Long value = em.createQuery("select count(a) from KbQuizAttempt a where a.userId = :userId " +
                        "and a.kbId = :kbId and a.answeredAt is not null", Long.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .getSingleResult();
        return value == null ? 0 : value;
    }

    @Transactional(readOnly = true)
    public long countByResult(Long userId, Long kbId, KbQuizAttempt.Result result) {
        Long value = em.createQuery("select count(a) from KbQuizAttempt a where a.userId = :userId " +
                        "and a.kbId = :kbId and a.result = :result", Long.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .setParameter("result", result.name())
                .getSingleResult();
        return value == null ? 0 : value;
    }

    /** 按知识点统计错题数：{categoryId, wrongCount}，错得多的排前面。 */
    @Transactional(readOnly = true)
    public List<Object[]> wrongByCategory(Long userId, Long kbId) {
        return em.createQuery("select a.categoryId, count(a) from KbQuizAttempt a where a.userId = :userId " +
                        "and a.kbId = :kbId and a.result in ('WRONG','UNSURE') and a.categoryId is not null " +
                        "group by a.categoryId order by count(a) desc", Object[].class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<Long> findWrongQuestionIds(Long userId, Long kbId, int limit) {
        return em.createQuery("select a.questionId from KbQuizAttempt a where a.userId = :userId " +
                        "and a.kbId = :kbId and a.result in ('WRONG','UNSURE') group by a.questionId " +
                        "order by count(a) desc, max(a.id) desc", Long.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .setMaxResults(Math.max(1, limit))
                .getResultList();
    }

    /** 每道题错了几次：{questionId, count}，错得多的排前面。 */
    @Transactional(readOnly = true)
    public List<Object[]> wrongCountPerQuestion(Long userId, Long kbId) {
        return em.createQuery("select a.questionId, count(a) from KbQuizAttempt a where a.userId = :userId " +
                        "and a.kbId = :kbId and a.result in ('WRONG','UNSURE') " +
                        "group by a.questionId order by count(a) desc", Object[].class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .getResultList();
    }

    /**
     * 做过多少道题（只算题库里还有的题）。
     *
     * <p>原来直接数 attempt 里的 question_id，重传文档把旧题删掉之后，
     * 这些孤儿作答还留在表里 —— 页面上就会出现"做过 13 / 题库总题数 8"这种自相矛盾的数字。</p>
     */
    @Transactional(readOnly = true)
    public long countDistinctQuestions(Long userId, Long kbId) {
        Long value = em.createQuery("select count(distinct a.questionId) from KbQuizAttempt a "
                        + "where a.userId = :userId and a.kbId = :kbId "
                        + "and a.questionId in (select q.id from KbQuestion q where q.kbId = :kbId)", Long.class)
                .setParameter("userId", userId)
                .setParameter("kbId", kbId)
                .getSingleResult();
        return value == null ? 0 : value;
    }
}
