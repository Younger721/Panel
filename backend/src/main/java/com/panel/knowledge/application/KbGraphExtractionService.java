package com.panel.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.domain.ContextSegment;
import com.panel.knowledge.domain.model.DocumentStatus;
import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbConcept;
import com.panel.knowledge.domain.model.KbDocument;
import com.panel.knowledge.domain.model.KbKind;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.persistence.KbConceptStore;
import com.panel.knowledge.infrastructure.persistence.KbGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图谱抽取：文档切片 → LLM 结构化 JSON → 概念/关系/证据。
 *
 * <p>只处理 TECH_DOC 库，默认关闭（{@code panel.kb.graph.enabled}）。按文档幂等：先删旧边与提及，再写入。</p>
 */
@Service
public class KbGraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(KbGraphExtractionService.class);
    private static final String EXTRACTOR_VERSION = "v1";
    private static final int MAX_CHUNK_CHARS = 1600;
    private static final int MAX_ENTITIES = 12;
    private static final int MAX_RELATIONS = 15;
    private static final Set<String> ENTITY_TYPES = Set.of(
            "CONCEPT", "COMPONENT", "COMMAND", "ERROR", "PATTERN", "METRIC");

    private final KbDocumentRepository documents;
    private final KnowledgeBaseRepository bases;
    private final KbChunkRepository chunks;
    private final ChatModelGateway gateway;
    private final KbConceptStore concepts;
    private final KbGraphStore graph;
    private final KbGraphSettings settings;
    private final TaskExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public KbGraphExtractionService(KbDocumentRepository documents,
                                    KnowledgeBaseRepository bases,
                                    KbChunkRepository chunks,
                                    ChatModelGateway gateway,
                                    KbConceptStore concepts,
                                    KbGraphStore graph,
                                    KbGraphSettings settings,
                                    @Qualifier("kbGraphExecutor") TaskExecutor executor) {
        this.documents = documents;
        this.bases = bases;
        this.chunks = chunks;
        this.gateway = gateway;
        this.concepts = concepts;
        this.graph = graph;
        this.settings = settings;
        this.executor = executor;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** 入库完成后调用；未开启时直接返回，不产生任何 LLM 调用。 */
    public void enqueue(Long documentId) {
        if (!settings.enabled() || documentId == null) {
            return;
        }
        executor.execute(() -> extractSafely(documentId));
    }

    /** 重建整个知识库的图谱（管理接口用）。 */
    public int rebuildKb(Long kbId) {
        List<KbDocument> ready = documents.findReadyByKbId(kbId);
        for (KbDocument document : ready) {
            enqueue(document.getId());
        }
        return ready.size();
    }

    public void extractSafely(Long documentId) {
        if (!running.add(documentId)) {
            return;
        }
        try {
            extract(documentId);
        } catch (Exception ex) {
            log.error("图谱抽取失败 documentId={}", documentId, ex);
        } finally {
            running.remove(documentId);
        }
    }

    void extract(Long documentId) {
        KbDocument document = documents.findById(documentId).orElse(null);
        if (document == null || document.status() != DocumentStatus.READY) {
            return;
        }
        KnowledgeBase base = bases.findById(document.kbId()).orElse(null);
        if (base == null || base.kind() != KbKind.TECH_DOC) {
            return;
        }
        List<KbChunk> list = chunks.findByDocumentIdOrderByOrdinal(documentId);
        graph.deleteByDocument(documentId);
        int edges = 0;
        for (KbChunk chunk : list) {
            try {
                edges += extractChunk(document, chunk);
            } catch (RuntimeException ex) {
                log.warn("图谱抽取单块失败 documentId={} chunkId={}：{}",
                        documentId, chunk.getId(), ex.getMessage());
            }
        }
        log.info("图谱抽取完成 documentId={} chunks={} edges={}", documentId, list.size(), edges);
    }

    private int extractChunk(KbDocument document, KbChunk chunk) {
        String text = chunk.text() == null ? "" : chunk.text();
        if (text.length() > MAX_CHUNK_CHARS) {
            text = text.substring(0, MAX_CHUNK_CHARS);
        }
        ChatModelCallResult result = gateway.completeStructuredJson(prompt(chunk, text),
                List.of(new ContextSegment("chunk-" + chunk.getId(), String.valueOf(document.getId()), text)));
        if (result == null || result.content() == null || result.content().isBlank()) {
            return 0;
        }
        JsonNode root = parseJson(result.content());
        if (root == null) {
            return 0;
        }
        Map<String, KbConcept> byName = new LinkedHashMap<>();
        JsonNode entities = root.path("entities");
        int index = 0;
        for (JsonNode node : entities) {
            if (index++ >= MAX_ENTITIES) {
                break;
            }
            String name = node.path("name").asText("").strip();
            if (name.length() < 2 || name.length() > 120) {
                continue;
            }
            String type = normalizeType(node.path("type").asText("CONCEPT"));
            List<String> aliases = new ArrayList<>();
            node.path("aliases").forEach(alias -> {
                String value = alias.asText("").strip();
                if (!value.isBlank() && !value.equalsIgnoreCase(name)) {
                    aliases.add(value);
                }
            });
            String description = node.path("description").asText("");
            KbConcept concept = concepts.findOrCreate(name, type, description.trim(),
                    KbConcept.aliasesJson(aliases));
            if (concept == null) {
                continue;
            }
            byName.put(KbConcept.normalize(name), concept);
            addMention(concept, chunk, name, aliases);
        }

        int edges = 0;
        for (JsonNode node : root.path("relations")) {
            if (edges >= MAX_RELATIONS) {
                break;
            }
            String type = node.path("type").asText("").strip().toUpperCase(Locale.ROOT);
            if (!settings.relationTypes().contains(type)) {
                continue;
            }
            double confidence = node.path("confidence").asDouble(0.7);
            if (confidence < settings.minConfidence()) {
                continue;
            }
            String evidence = node.path("evidence").asText("").strip();
            if (evidence.isBlank()) {
                continue;
            }
            KbConcept source = resolve(byName, node.path("source").asText(""));
            KbConcept target = resolve(byName, node.path("target").asText(""));
            if (source == null || target == null) {
                continue;
            }
            graph.addEdge(document.kbId(), source.getId(), target.getId(), type, confidence,
                    chunk.getId(), document.getId(), EXTRACTOR_VERSION);
            edges++;
        }
        return edges;
    }

    private void addMention(KbConcept concept, KbChunk chunk, String name, List<String> aliases) {
        String text = chunk.text() == null ? "" : chunk.text();
        String matched = name;
        int offset = text.indexOf(name);
        if (offset < 0) {
            for (String alias : aliases) {
                offset = text.indexOf(alias);
                if (offset >= 0) {
                    matched = alias;
                    break;
                }
            }
        }
        if (offset < 0) {
            graph.addMention(concept.getId(), chunk.getId(), chunk.kbId(), null, null, null);
            return;
        }
        int end = Math.min(text.length(), offset + matched.length());
        String quote = text.substring(offset, end);
        graph.addMention(concept.getId(), chunk.getId(), chunk.kbId(), offset, end,
                quote.length() <= 255 ? quote : quote.substring(0, 255));
    }

    private static KbConcept resolve(Map<String, KbConcept> byName, String raw) {
        String normalized = KbConcept.normalize(raw);
        return normalized.isBlank() ? null : byName.get(normalized);
    }

    private static String normalizeType(String raw) {
        String type = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        return ENTITY_TYPES.contains(type) ? type : "CONCEPT";
    }

    private JsonNode parseJson(String content) {
        String value = content.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(value.substring(start, end + 1));
        } catch (Exception ex) {
            log.warn("图谱抽取 JSON 解析失败：{}", ex.getMessage());
            return null;
        }
    }

    private static String prompt(KbChunk chunk, String text) {
        String heading = chunk.heading() == null ? "" : chunk.heading();
        return """
                你是技术文档的知识图谱抽取器。只输出 JSON，不要解释、不要 markdown 代码块。

                允许的实体类型：CONCEPT, COMPONENT, COMMAND, ERROR, PATTERN, METRIC
                允许的关系类型：DEPENDS_ON, PART_OF, CONTRASTS_WITH, CAUSES, PREREQUISITE, SOLVES, CONFIGURES, MENTIONS

                规则：
                1. 只抽正文里明确出现的技术概念，不要补常识。
                2. 每条关系必须给出正文证据（20~80 字），找不到证据就不要输出。
                3. 同一实体只出现一次，别名放进 aliases。
                4. confidence 取 0~1，模棱两可给 0.5 以下。
                5. 最多 12 个实体、15 条关系。

                输出格式：
                {"entities":[{"name":"RDB","type":"CONCEPT","aliases":["Redis Database"],"description":"全量快照持久化"}],
                 "relations":[{"source":"RDB","target":"AOF","type":"CONTRASTS_WITH","evidence":"RDB 恢复快但可能丢数据","confidence":0.9}]}

                标题：%s
                正文：%s
                """.formatted(heading, text);
    }
}
