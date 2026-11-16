package com.panel.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** ReportAgent 的产出；{@code overallScore} 由代码重算，不采信模型给的总分。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewReportData(String summary, List<String> strengths, List<String> improvements,
                                  List<SkillScore> skillScores) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillScore(String skill, int score, String comment) {
    }

    public InterviewReportData {
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        improvements = improvements == null ? List.of() : List.copyOf(improvements);
        skillScores = skillScores == null ? List.of() : List.copyOf(skillScores);
    }
}
