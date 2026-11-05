package com.nova.ai.infrastructure;

import com.nova.ai.application.RecommendationCandidate;
import com.nova.ai.application.RecommendationCandidateSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 兜底候选来源：返回空列表，保证 {@code ai:recommend} 接口可用而不至于启动失败。
 *
 * <p>迁移背景：GymMind 里真正的实现是 {@code ExerciseRecommendationCandidateSource}
 * （读动作库），属于健身域，迁移时被丢弃；这个兜底类当时没加 {@code @Component}，
 * 于是整个 {@code RecommendationCandidateSource} 没有豆子，应用起不来。</p>
 *
 * <p>招聘域的正式实现应来自岗位/简历模块（按 {@code goal} 匹配岗位，把
 * {@code contraindications} 换成「不考虑的条件」），落地后给它加 {@code @Primary}
 * 或直接替换本类。</p>
 */
@Component
public class InMemoryRecommendationCandidateSource implements RecommendationCandidateSource {

    @Override
    public List<RecommendationCandidate> findByTenant(Long tenantId) {
        return List.of();
    }
}
