package com.nova.interview.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 简历的结构化解析结果（一份简历一条）。
 *
 * <p>为什么存 JSON 而不是拆成 ten 张关系表：结构化字段是<b>模型输出</b>，
 * 维度会随提示词迭代变化（这个月加"项目亮点"，下个月加"稳定性"），
 * 拆表意味着每次调整都要迁移。用 JSON 存原文，把"要哪些字段"交给代码里的
 * record 定义，改一处即可。</p>
 *
 * <p>但访问频繁的两个标量（技能数、工作年限）单独落列，方便后续筛选/统计。</p>
 */
@Entity
@Table(name = "resume_profile")
public class ResumeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long resumeId;

    /** 模型返回的完整结构化 JSON（已校验可解析）。 */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    /** 工作年限，模型抽取不到时为 null。 */
    private Integer yearsOfExperience;

    /** 技能数量，便于列表页快速展示。 */
    private Integer skillCount;

    /** 产出该结果的模型名，便于排查"同一简历换模型后结果变了"。 */
    @Column(length = 128)
    private String modelName;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ResumeProfile() {
    }

    public static ResumeProfile create(Long resumeId, String payloadJson, Integer yearsOfExperience,
                                       Integer skillCount, String modelName) {
        ResumeProfile profile = new ResumeProfile();
        profile.resumeId = resumeId;
        profile.payloadJson = payloadJson;
        profile.yearsOfExperience = yearsOfExperience;
        profile.skillCount = skillCount;
        profile.modelName = modelName;
        return profile;
    }

    public void replace(String payloadJson, Integer yearsOfExperience, Integer skillCount, String modelName) {
        this.payloadJson = payloadJson;
        this.yearsOfExperience = yearsOfExperience;
        this.skillCount = skillCount;
        this.modelName = modelName;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Integer getYearsOfExperience() {
        return yearsOfExperience;
    }

    public Integer getSkillCount() {
        return skillCount;
    }

    public String getModelName() {
        return modelName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
