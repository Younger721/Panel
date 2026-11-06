package com.nova.interview.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一份简历的深度分析结果：Markdown 全文 + 块结构 + 模块/小点批注。
 *
 * <p>刻意存成 JSON 而不是拆成 module/point 两张表：读取时永远整棵树一起读
 * （批注页要渲染整篇），也没有"跨简历查某个小点"的查询需求，拆表只会多两次 join。</p>
 */
@Entity
@Table(name = "resume_analysis")
public class ResumeAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_id", nullable = false, unique = true)
    private Long resumeId;

    /** 转换后的 Markdown 全文，可直接下载。 */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String markdown;

    /** 块结构：批注锚点的坐标系。 */
    @Lob
    @Column(name = "blocks_json", nullable = false, columnDefinition = "LONGTEXT")
    private String blocksJson;

    /** 模块 → 小点 → 点评/优化/参考问题。 */
    @Lob
    @Column(name = "modules_json", nullable = false, columnDefinition = "LONGTEXT")
    private String modulesJson;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ResumeAnalysis() {
    }

    public static ResumeAnalysis create(Long resumeId, String markdown, String blocksJson,
                                        String modulesJson, String modelName) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.resumeId = resumeId;
        analysis.markdown = markdown;
        analysis.blocksJson = blocksJson;
        analysis.modulesJson = modulesJson;
        analysis.modelName = modelName;
        analysis.createdAt = Instant.now();
        return analysis;
    }

    public void replace(String markdown, String blocksJson, String modulesJson, String modelName) {
        this.markdown = markdown;
        this.blocksJson = blocksJson;
        this.modulesJson = modulesJson;
        this.modelName = modelName;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public String getMarkdown() {
        return markdown;
    }

    public String getBlocksJson() {
        return blocksJson;
    }

    public String getModulesJson() {
        return modulesJson;
    }

    public String getModelName() {
        return modelName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
