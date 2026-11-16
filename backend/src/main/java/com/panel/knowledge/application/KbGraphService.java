package com.panel.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.knowledge.domain.model.KbConcept;
import com.panel.knowledge.infrastructure.persistence.KbConceptStore;
import com.panel.knowledge.infrastructure.persistence.KbGraphStore;
import org.springframework.stereotype.Service;

import java.util.List;

/** 图谱只读视图：规模统计 + 概念列表（管理页/调试用）。 */
@Service
public class KbGraphService {

    private final KbConceptStore concepts;
    private final KbGraphStore graph;
    private final ObjectMapper mapper = new ObjectMapper();

    public KbGraphService(KbConceptStore concepts, KbGraphStore graph) {
        this.concepts = concepts;
        this.graph = graph;
    }

    public KbGraphStats stats(Long kbId) {
        return new KbGraphStats((int) concepts.countByKb(kbId), graph.countEdges(kbId));
    }

    public List<KbConceptView> concepts(Long kbId, int limit) {
        return concepts.findByKb(kbId, limit).stream()
                .map(this::toView)
                .toList();
    }

    private KbConceptView toView(KbConcept concept) {
        return new KbConceptView(
                concept.getId(),
                concept.canonicalName(),
                concept.type(),
                concept.description(),
                aliases(concept.aliasesJson()));
    }

    private List<String> aliases(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }
}
