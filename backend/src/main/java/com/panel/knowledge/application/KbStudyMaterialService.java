package com.panel.knowledge.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「这个技能我弱，该补什么」——按技能名去知识库里找学习材料。
 *
 * <p>为什么不用知识图谱：本机 992 个切片、37 个概念是有的，但
 * {@code kb_graph_mention}/{@code kb_graph_edge} 两张表是空的（概念抽取没跑过），
 * 图谱路线现在没有数据可用。而"概念名 + 切片正文 + 题目正文"三张表已经有内容，
 * 直接按词面找就能给出可用的推荐——不必先烧一轮模型抽取。</p>
 *
 * <p>技能名是模型写的自由文本（"Redis与分布式锁"、"并发编程"），不会和概念名逐字相等，
 * 所以先按分隔符拆成片段再匹配。拆完仍然逐字匹配，不做同义词扩展：
 * 宁可少给两条准的，也不要给一堆看着相关其实无关的。</p>
 */
@Service
public class KbStudyMaterialService {

    private static final Logger log = LoggerFactory.getLogger(KbStudyMaterialService.class);

    /** 技能名里常见的连接词，拆开才好匹配（"Redis与分布式锁" → Redis / 分布式锁） */
    private static final String SPLITTERS = "[与和、,，/|\\-+&]+";

    /** 太短的片段会命中一堆无关内容（"锁"、"J"），直接丢掉 */
    private static final int MIN_FRAGMENT = 2;

    @PersistenceContext
    private EntityManager em;

    public record Concept(Long id, String name, String type, String description) {
    }

    public record Question(Long id, String stem, String difficulty) {
    }

    public record Document(Long id, String fileName, int hits) {
    }

    /** 一个技能的复习材料 */
    public record Material(String skill, List<String> keywords,
                           List<Concept> concepts, List<Question> questions, List<Document> documents) {
    }

    public Material findFor(String skill, int limit) {
        List<String> keywords = keywordsOf(skill);
        if (keywords.isEmpty()) {
            return new Material(skill, List.of(), List.of(), List.of(), List.of());
        }
        List<Concept> concepts = findConcepts(keywords, limit);
        List<Document> documents = findDocuments(keywords, limit);
        List<Question> questions = findQuestions(keywords, limit * 2);
        log.debug("Study material for skill={}: keywords={}, concepts={}, docs={}, questions={}",
                skill, keywords, concepts.size(), documents.size(), questions.size());
        return new Material(skill, keywords, concepts, questions, documents);
    }

    /** 拆出用于匹配的片段，去重保序。 */
    static List<String> keywordsOf(String skill) {
        if (skill == null || skill.isBlank()) {
            return List.of();
        }
        Set<String> fragments = new LinkedHashSet<>();
        for (String part : skill.split(SPLITTERS)) {
            String trimmed = part.strip();
            if (trimmed.length() >= MIN_FRAGMENT) {
                fragments.add(trimmed);
            }
        }
        return List.copyOf(fragments);
    }

    private List<Concept> findConcepts(List<String> keywords, int limit) {
        StringBuilder jpql = new StringBuilder(
                "select c from KbConcept c where ");
        appendAnyMatch(jpql, "c.canonicalName", "c.aliasesJson", keywords.size());
        jpql.append(" order by c.id");
        var query = em.createQuery(jpql.toString(), com.panel.knowledge.domain.model.KbConcept.class);
        bindKeywords(query, keywords);
        query.setMaxResults(limit);
        List<Concept> result = new ArrayList<>();
        for (var concept : query.getResultList()) {
            result.add(new Concept(concept.getId(), concept.canonicalName(), concept.type(), concept.description()));
        }
        return result;
    }

    private List<Document> findDocuments(List<String> keywords, int limit) {
        // 切片正文里提到关键词文档最多，就算最该读的那几篇
        StringBuilder sql = new StringBuilder(
                "select d.id, d.file_name, count(*) as hits from kb_chunk c "
                + "join kb_document d on d.id = c.document_id where ");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(" or ");
            }
            sql.append("c.text like :kw").append(i);
        }
        sql.append(" group by d.id, d.file_name order by hits desc, d.id desc");
        var query = em.createNativeQuery(sql.toString());
        for (int i = 0; i < keywords.size(); i++) {
            query.setParameter("kw" + i, "%" + keywords.get(i) + "%");
        }
        query.setMaxResults(limit);
        List<Document> result = new ArrayList<>();
        for (Object row : query.getResultList()) {
            Object[] cells = (Object[]) row;
            result.add(new Document(((Number) cells[0]).longValue(), String.valueOf(cells[1]),
                    ((Number) cells[2]).intValue()));
        }
        return result;
    }

    private List<Question> findQuestions(List<String> keywords, int limit) {
        StringBuilder jpql = new StringBuilder(
                "select q from KbQuestion q where ");
        appendAnyMatch(jpql, "q.stem", "q.tagsJson", keywords.size());
        // 难的先给：弱项上先做难题才有区分度
        jpql.append(" order by case when q.difficulty = 'HARD' then 0 else 1 end, q.id desc");
        var query = em.createQuery(jpql.toString(), com.panel.knowledge.domain.model.KbQuestion.class);
        bindKeywords(query, keywords);
        query.setMaxResults(limit);
        List<Question> result = new ArrayList<>();
        for (var question : query.getResultList()) {
            result.add(new Question(question.getId(), question.stem(), question.difficulty()));
        }
        return result;
    }

    private static void appendAnyMatch(StringBuilder jpql, String first, String second, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                jpql.append(" or ");
            }
            jpql.append(first).append(" like :kw").append(i)
                    .append(" or ").append(second).append(" like :kw").append(i);
        }
    }

    private static void bindKeywords(jakarta.persistence.Query query, List<String> keywords) {
        for (int i = 0; i < keywords.size(); i++) {
            query.setParameter("kw" + i, "%" + keywords.get(i) + "%");
        }
    }
}
