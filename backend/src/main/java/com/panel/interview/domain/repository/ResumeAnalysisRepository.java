package com.panel.interview.domain.repository;

import com.panel.interview.domain.model.ResumeAnalysis;

import java.util.Optional;

public interface ResumeAnalysisRepository {

    Optional<ResumeAnalysis> findByResumeId(Long resumeId);

    ResumeAnalysis save(ResumeAnalysis analysis);

    /**
     * 正文变了就把旧批注作废。
     *
     * <p>不失效的后果：用户在批注页看到"新简历 + 旧批注"，
     * `quote`/`anchor` 的字符偏移对应旧文本，高亮会框到完全无关的句子上。</p>
     */
    void deleteByResumeId(Long resumeId);
}
