package com.panel.interview.domain.repository;

import com.panel.interview.domain.model.ResumeProfile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 简历结构化画像仓储。 */
public interface ResumeProfileRepository {

    ResumeProfile save(ResumeProfile profile);

    Optional<ResumeProfile> findByResumeId(Long resumeId);

    /** 列表页一次取回所有画像，避免「N 条简历 N+1 次查询」。 */
    List<ResumeProfile> findByResumeIdIn(Collection<Long> resumeIds);

    /** 正文变了，旧画像（年限/技能/项目）同样过期，不能继续展示。 */
    void deleteByResumeId(Long resumeId);
}
