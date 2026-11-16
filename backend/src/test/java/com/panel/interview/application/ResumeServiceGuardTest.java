package com.panel.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.repository.ResumeAnalysisRepository;
import com.panel.interview.domain.repository.ResumeProfileRepository;
import com.panel.interview.domain.repository.ResumeRepository;
import com.panel.knowledge.application.KnowledgeObjectStore;
import com.panel.shared.async.PersistentJobQueue;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 简历「排队中/解析中」期间的写入守卫。
 *
 * <p>这些用例守的是一条具体的坑：{@code reanalyze} 会把状态重置成 PENDING 再投递任务，
 * 所以"PENDING"不是"空闲"，而是"已经在队列里"。原来只有 PARSING/ANALYZING 才算忙，
 * 于是连点两下「重新分析」会重复投递；保存正文也会被静默排队到后面那次解析里。</p>
 */
class ResumeServiceGuardTest {

    private ResumeRepository resumes;
    private ResumeProfileRepository profiles;
    private ResumeAnalysisRepository analyses;
    private KnowledgeObjectStore store;
    private PersistentJobQueue jobs;
    private ResumeAccessGuard access;
    private ResumeService service;

    @BeforeEach
    void setUp() {
        resumes = mock(ResumeRepository.class);
        profiles = mock(ResumeProfileRepository.class);
        analyses = mock(ResumeAnalysisRepository.class);
        store = mock(KnowledgeObjectStore.class);
        jobs = mock(PersistentJobQueue.class);
        access = mock(ResumeAccessGuard.class);
        service = new ResumeService(resumes, profiles, analyses, store, jobs, new ObjectMapper(), access);
        when(profiles.findByResumeId(anyLong())).thenReturn(Optional.empty());
        when(resumes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Resume pendingResume() {
        // Resume.create 的初始状态就是 PENDING——正是"已经排队、还没开始解析"的那个窗口
        Resume resume = Resume.create("a.txt", "text/plain", "resume/1/a.txt", "hash", 7L);
        when(access.require(1L)).thenReturn(resume);
        return resume;
    }

    @Test
    void rejectsSaveWhileQueued() {
        pendingResume();

        assertThatThrownBy(() -> service.saveContent(1L, "新正文", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 一次对象存储写入都不该发生：挡住了就不该有副作用
        verifyNoInteractions(store);
        verify(jobs, never()).enqueue(anyString(), any());
    }

    @Test
    void rejectsReanalyzeWhileQueued() {
        pendingResume();

        assertThatThrownBy(() -> service.reanalyze(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(jobs, never()).enqueue(anyString(), any());
    }

    /** reset 的职责就是解卡，所以它在 PENDING 时也必须能用（否则卡在排队中的简历没救）。 */
    @Test
    void allowsResetWhileQueued() {
        pendingResume();

        assertThat(service.reset(1L).status().name()).isEqualTo("PENDING");
        verify(jobs).enqueue(anyString(), any());
    }
}
