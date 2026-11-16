package com.panel.knowledge.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 面试作答信号的只读视图。
 *
 * <p>{@code interview_turn.skill} 是面试官给的"这轮考的是什么"，自由文本；
 * {@code final_score} 是这一轮的最终得分。两者都齐的轮次才能折算成一次能力观测。</p>
 */
@Repository
public class KbInterviewSignalStore {

    /** 一行原始信号：turnId / skill / score / difficulty / 提问时间。 */
    public record Signal(Long turnId, String skill, Integer score, String difficulty,
                         java.time.Instant askedAt) {
    }

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<Signal> findSignals(int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select t.id, t.skill, t.final_score, t.difficulty, t.asked_at " +
                                "from interview_turn t " +
                                "where t.skill is not null and t.skill <> '' and t.final_score is not null " +
                                "order by t.asked_at asc, t.id asc")
                .setMaxResults(Math.max(1, limit))
                .getResultList();
        List<Signal> result = new java.util.ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new Signal(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    row[2] == null ? null : ((Number) row[2]).intValue(),
                    (String) row[3],
                    row[4] == null ? null : ((java.sql.Timestamp) row[4]).toInstant()));
        }
        return result;
    }
}
