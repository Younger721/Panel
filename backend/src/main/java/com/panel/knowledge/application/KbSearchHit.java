package com.panel.knowledge.application;

import java.util.List;

public record KbSearchHit(
        String chunkKey,
        Long documentId,
        Long kbId,
        String documentName,
        Integer ordinal,
        String heading,
        Integer pageNo,
        String text,
        double score,
        Double rerankScore,
        Integer keywordRank,
        Integer vectorRank,
        List<String> sources) {
}
