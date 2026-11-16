package com.panel.knowledge.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** K2 检索参数；后续问答（K3）复用同一套召回/融合预算。 */
@Component
public class KbSearchSettings {

    private final int recallTopK;
    private final int fusionTopK;
    private final int maxPerDocument;
    private final boolean rerankEnabled;

    public KbSearchSettings(
            @Value("${panel.kb.search.recall-top-k:30}") int recallTopK,
            @Value("${panel.kb.search.fusion-top-k:20}") int fusionTopK,
            @Value("${panel.kb.search.max-per-document:2}") int maxPerDocument,
            @Value("${panel.kb.search.rerank-enabled:true}") boolean rerankEnabled) {
        this.recallTopK = Math.max(1, recallTopK);
        this.fusionTopK = Math.max(1, fusionTopK);
        this.maxPerDocument = Math.max(1, maxPerDocument);
        this.rerankEnabled = rerankEnabled;
    }

    public int recallTopK() {
        return recallTopK;
    }

    public int fusionTopK() {
        return fusionTopK;
    }

    public int maxPerDocument() {
        return maxPerDocument;
    }

    public boolean rerankEnabled() {
        return rerankEnabled;
    }
}
