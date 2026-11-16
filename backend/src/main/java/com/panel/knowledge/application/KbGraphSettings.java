package com.panel.knowledge.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** 图谱通道参数：默认关闭，打开后入库时异步抽取，检索时多一路 graphHits。 */
@Component
public class KbGraphSettings {

    private final boolean enabled;
    private final int maxHops;
    private final int maxNeighbors;
    private final int maxChunks;
    private final double minConfidence;
    private final List<String> relationTypes;

    public KbGraphSettings(
            @Value("${panel.kb.graph.enabled:false}") boolean enabled,
            @Value("${panel.kb.graph.max-hops:1}") int maxHops,
            @Value("${panel.kb.graph.max-neighbors:20}") int maxNeighbors,
            @Value("${panel.kb.graph.max-chunks:30}") int maxChunks,
            @Value("${panel.kb.graph.min-confidence:0.6}") double minConfidence,
            @Value("${panel.kb.graph.relation-types:CONTRASTS_WITH,CAUSES,PREREQUISITE,DEPENDS_ON,PART_OF,SOLVES,CONFIGURES,MENTIONS}")
            String relationTypes) {
        this.enabled = enabled;
        this.maxHops = Math.max(1, Math.min(2, maxHops));
        this.maxNeighbors = Math.max(1, maxNeighbors);
        this.maxChunks = Math.max(1, maxChunks);
        this.minConfidence = Math.max(0, Math.min(1, minConfidence));
        this.relationTypes = Arrays.stream(relationTypes.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public boolean enabled() { return enabled; }
    public int maxHops() { return maxHops; }
    public int maxNeighbors() { return maxNeighbors; }
    public int maxChunks() { return maxChunks; }
    public double minConfidence() { return minConfidence; }
    public List<String> relationTypes() { return relationTypes; }
}
