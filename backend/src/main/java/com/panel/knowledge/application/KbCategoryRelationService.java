package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbCategoryRelation;
import com.panel.knowledge.infrastructure.persistence.KbCategoryRelationStore;
import com.panel.knowledge.infrastructure.persistence.KbCategoryStore;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识点横切关系：树之外的“相关 / 前置 / 对比”。
 *
 * <p>只做节点级 1 跳，前端在知识点页底部展示；不做实体图谱，也不上图数据库。</p>
 */
@Service
public class KbCategoryRelationService {

    private final KbCategoryRelationStore relations;
    private final KbCategoryStore categories;

    public KbCategoryRelationService(KbCategoryRelationStore relations, KbCategoryStore categories) {
        this.relations = relations;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<KbQuizViews.Relation> list(Long kbId, Long categoryId) {
        Map<Long, String> names = names(kbId);
        return relations.findByCategory(kbId, categoryId).stream()
                .map(relation -> toView(relation, names))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KbQuizViews.Relation> listAll(Long kbId) {
        Map<Long, String> names = names(kbId);
        return relations.findByKb(kbId).stream().map(relation -> toView(relation, names)).toList();
    }

    @Transactional
    public KbQuizViews.Relation create(Long kbId, Long fromCategoryId, Long toCategoryId,
                                       String relation, Double weight, String note) {
        KbCategory from = categories.findById(fromCategoryId);
        KbCategory to = categories.findById(toCategoryId);
        if (from == null || to == null || !kbId.equals(from.kbId()) || !kbId.equals(to.kbId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "两个知识点必须属于当前知识库");
        }
        KbCategoryRelation created;
        try {
            created = KbCategoryRelation.create(kbId, fromCategoryId, toCategoryId, relation, weight, note);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        for (KbCategoryRelation existing : relations.findByCategory(kbId, fromCategoryId)) {
            boolean same = existing.fromCategoryId().equals(fromCategoryId)
                    && existing.toCategoryId().equals(toCategoryId)
                    && existing.relation().equals(created.relation());
            if (same) {
                existing.reweight(weight, note);
                return toView(relations.save(existing), names(kbId));
            }
        }
        return toView(relations.save(created), names(kbId));
    }

    @Transactional
    public void delete(Long kbId, Long relationId) {
        KbCategoryRelation relation = relations.findById(relationId);
        if (relation == null || !kbId.equals(relation.kbId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "关系不存在：" + relationId);
        }
        relations.delete(relation);
    }

    private KbQuizViews.Relation toView(KbCategoryRelation relation, Map<Long, String> names) {
        return new KbQuizViews.Relation(relation.getId(), relation.kbId(), relation.fromCategoryId(),
                names.get(relation.fromCategoryId()), relation.toCategoryId(),
                names.get(relation.toCategoryId()), relation.relation(),
                relation.weight() == null ? 0.5d : relation.weight(), relation.note(), relation.createdAt());
    }

    private Map<Long, String> names(Long kbId) {
        Map<Long, String> names = new HashMap<>();
        List<KbCategory> all = new ArrayList<>(categories.findByKb(kbId));
        for (KbCategory category : all) {
            names.put(category.getId(), category.name());
        }
        return names;
    }
}
