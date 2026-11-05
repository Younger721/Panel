package com.nova.interview.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 简历结构化抽取的契约（模型必须按这个形状返回 JSON）。
 *
 * <p>字段刻意保持扁平且少：每多一个字段，提示词就更难被稳定遵守一次。
 * 需要新维度时优先扩展 {@code highlights}/{@code summary}，而不是加新数组。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedResume(
        List<String> skills,
        Integer yearsOfExperience,
        List<Project> projects,
        List<Education> education,
        List<String> highlights,
        String summary) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(String name, String role, List<String> highlights) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(String school, String degree, String major, String period) {
    }

    public ParsedResume {
        skills = skills == null ? List.of() : List.copyOf(skills);
        projects = projects == null ? List.of() : List.copyOf(projects);
        education = education == null ? List.of() : List.copyOf(education);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }
}
