package com.panel.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 岗位匹配结果：这份简历对这份 JD 的账。
 *
 * <p>和评分一样，{@code evidence} 要能回到简历原文，否则"匹配"就是模型在客气。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobFit(String headline, Integer score, List<Match> matched, List<Gap> gaps,
                     List<String> prioritySkills) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Match(String skill, String evidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gap(String skill, String why) {
    }

    public JobFit {
        matched = matched == null ? List.of() : List.copyOf(matched);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        prioritySkills = prioritySkills == null ? List.of() : List.copyOf(prioritySkills);
    }

    public static JobFit empty() {
        return new JobFit(null, null, List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return matched.isEmpty() && gaps.isEmpty() && prioritySkills.isEmpty();
    }
}
