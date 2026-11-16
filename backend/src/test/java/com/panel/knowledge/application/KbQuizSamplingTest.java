package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbQuizAttempt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 抽题算法：分层不能把 5 题砸在一个节点上，也不该每次都抽到已经做对过的题。 */
class KbQuizSamplingTest {

    private static List<Long> ids(long from, long to) {
        List<Long> ids = new ArrayList<>();
        for (long id = from; id <= to; id++) {
            ids.add(id);
        }
        return ids;
    }

    @Test
    void spreadsAcrossKnowledgePointsAndNeverRepeats() {
        List<Long> pool = ids(1, 30);
        Map<Long, Long> categoryOf = new HashMap<>();
        for (long id = 1; id <= 30; id++) {
            categoryOf.put(id, (id - 1) / 10 + 1);
        }

        List<Long> picked = KbQuizService.sample(pool, categoryOf, Map.of(), Map.of(), 5);

        assertThat(picked).hasSize(5).doesNotHaveDuplicates();
        Map<Long, Long> perNode = new HashMap<>();
        picked.forEach(id -> perNode.merge(categoryOf.get(id), 1L, Long::sum));
        assertThat(perNode).hasSize(3);
        assertThat(perNode.values()).allMatch(count -> count <= 2);
    }

    @Test
    void fallsBackToWholePoolWhenItIsSmallerThanTarget() {
        List<Long> pool = ids(1, 3);
        Map<Long, Long> categoryOf = Map.of(1L, 1L, 2L, 1L, 3L, 2L);

        List<Long> picked = KbQuizService.sample(pool, categoryOf, Map.of(), Map.of(), 10);

        assertThat(picked).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void prefersQuestionsAnsweredWrongLastTime() {
        List<Long> pool = ids(1, 10);
        Map<Long, Long> categoryOf = new HashMap<>();
        pool.forEach(id -> categoryOf.put(id, 1L));

        KbQuizAttempt wrongOnce = KbQuizAttempt.create(1L, 1L, 1L, 1L, 0, 1L);
        wrongOnce.grade(KbQuizAttempt.Result.WRONG);
        Map<Long, KbQuizAttempt> latest = Map.of(1L, wrongOnce);

        int hits = 0;
        for (int round = 0; round < 300; round++) {
            if (KbQuizService.sample(pool, categoryOf, latest, Map.of(), 1).contains(1L)) {
                hits++;
            }
        }

        // 权重 3.0 对 9 题各 1.2：单次概率约 21.7%，300 次期望约 65 次；阈值留足余量。
        assertThat(hits).isGreaterThan(35);
    }

    @Test
    void doesNotRepeatRecentlyCorrectQuestionEveryTime() {
        List<Long> pool = ids(1, 4);
        Map<Long, Long> categoryOf = new HashMap<>();
        pool.forEach(id -> categoryOf.put(id, 1L));

        KbQuizAttempt correct = KbQuizAttempt.create(1L, 1L, 1L, 1L, 0, 1L);
        correct.grade(KbQuizAttempt.Result.CORRECT);
        Map<Long, KbQuizAttempt> latest = Map.of(1L, correct);

        int hits = 0;
        for (int round = 0; round < 300; round++) {
            if (KbQuizService.sample(pool, categoryOf, latest, Map.of(), 1).contains(1L)) {
                hits++;
            }
        }

        // 刚做对的权重 0.25，其余 1.2：单次约 6.5%，300 次期望约 20 次。
        assertThat(hits).isLessThan(45);
    }
}
