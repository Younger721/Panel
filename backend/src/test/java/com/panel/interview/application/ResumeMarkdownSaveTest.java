package com.panel.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.repository.ResumeAnalysisRepository;
import com.panel.interview.domain.repository.ResumeProfileRepository;
import com.panel.interview.domain.repository.ResumeRepository;
import com.panel.knowledge.application.KnowledgeObjectStore;
import com.panel.shared.async.PersistentJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双源保存：Markdown 内容稿 vs 原版 PDF。
 *
 * <p>守的是三个具体的坑：</p>
 * <ol>
 *   <li>md 保存把 {@code source_*} 一起改掉 → 原版预览、原位编辑、导出 PDF 全废；</li>
 *   <li>md 保存时没传 edits 就把 {@code edit_layer} 洗成 null → 用户在 PDF 里改的字从版面上消失；</li>
 *   <li>纯文本保存不清 md 指针 → 下一次解析读回旧 md，把刚存的正文盖回去。</li>
 * </ol>
 */
class ResumeMarkdownSaveTest {

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

    /** 解析完成的 PDF 简历：PENDING 会被 inFlight 挡成 409，所以先推到 PARSED。 */
    private Resume parsedPdfResume() {
        Resume resume = Resume.create("张三-简历.pdf", "application/pdf",
                "resume/1/original.pdf", "originalsha", 7L);
        resume.markParsing();
        resume.markParsed();
        when(access.require(1L)).thenReturn(resume);
        return resume;
    }

    @Test
    void markdown保存只动内容稿指针原版保持不动() {
        Resume resume = parsedPdfResume();

        service.saveContent(1L, "# 张三\n\n## 项目经历\n\n### 平台 | 2024.03 – 至今\n- 做了 x", null, "markdown");

        // 内容稿落在 md 上，解析器靠这两个值才会走 markdown 分支
        assertThat(resume.hasMarkdownSource()).isTrue();
        assertThat(resume.getEffectiveObjectKey()).endsWith(".md");
        assertThat(resume.getEffectiveContentType()).isEqualTo("text/markdown");
        assertThat(resume.getEffectiveFileName()).isEqualTo("张三-简历.md");

        // 版式模板一动不碰：这三条链路全靠它
        assertThat(resume.getObjectKey()).isEqualTo("resume/1/original.pdf");
        assertThat(resume.getSourceObjectKey()).isEqualTo("resume/1/original.pdf");
        assertThat(resume.getSourceContentType()).isEqualTo("application/pdf");
        assertThat(resume.getFileName()).isEqualTo("张三-简历.pdf");

        verify(store).put(anyString(), any(byte[].class), eq("text/markdown"));
    }

    @Test
    void markdown保存不传edits时保留原有原位改动() {
        Resume resume = parsedPdfResume();
        resume.applyEditLayer("{\"0\":\"<p>PDF 里改过的一段</p>\"}");

        service.saveContent(1L, "# 张三\n\n## 项目经历\n\n- 新内容", null, "markdown");

        assertThat(resume.getEditLayer()).contains("PDF 里改过的一段");
    }

    @Test
    void 纯文本保存会清掉过期的md内容稿() {
        Resume resume = parsedPdfResume();
        service.saveContent(1L, "# 张三\n\n## 项目经历\n\n- 旧内容", null, "markdown");
        assertThat(resume.hasMarkdownSource()).isTrue();

        // md 保存会把状态推回 PENDING（等重解析），inFlight 会挡第二次保存；
        // 真实流程里前端也是在解析完成后才允许再存一次。
        resume.markParsing();
        resume.markParsed();

        // 用户回到 PDF 原位编辑保存：正文已经换了一份，旧 md 再留着会被下一次解析读回去
        service.saveContent(1L, "张三\n项目经历\n- 最终内容", Map.of("0", "<p>最终内容</p>"), "text");

        assertThat(resume.hasMarkdownSource()).isFalse();
        // id 未持久化时为 null，所以只钉扩展名与文件名：真实记录里是 resume/{id}/edited-{ts}.txt
        assertThat(resume.getEffectiveObjectKey()).startsWith("resume/").endsWith(".txt");
        assertThat(resume.getEffectiveContentType()).isEqualTo("text/plain");
        assertThat(resume.getEffectiveFileName()).isEqualTo("张三-简历.txt");
    }
}
