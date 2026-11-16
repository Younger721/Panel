package com.panel.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Interviewer 产出的一道题。{@code pointId} 必须来自考察点矩阵。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuestionDraft(String question, String type, String difficulty, String pointId,
                            String intent, List<String> followUps) {

    public QuestionDraft {
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }
}
