package com.panel.knowledge.application;

import java.util.List;

/** 回答里的一条引用：序号与答案正文里的 [n] 对应。 */
public record KbChatCitation(
        int index,
        String chunkKey,
        Long documentId,
        String documentName,
        String heading,
        Integer pageNo,
        String excerpt,
        double score) {

    public static List<KbChatCitation> empty() {
        return List.of();
    }
}
