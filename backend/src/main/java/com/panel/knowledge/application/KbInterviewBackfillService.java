package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbSkillInterviewImport;
import com.panel.knowledge.infrastructure.persistence.KbCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbInterviewSignalStore;
import com.panel.knowledge.infrastructure.persistence.KbSkillInterviewImportStore;

import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面试信号回填：把历史 {@code interview_turn}（skill + final_score）折算成知识点能力值。
 *
 * <p>为什么需要回填：能力值表是新的，而面试记录已经攒了几十轮。不回填的话，
 * "面试弱但刷题还行"这种判断永远出不来，因为面试那一半信号根本没进账。</p>
 *
 * <p>三个设计约束：</p>
 * <ol>
 *   <li><b>幂等</b>：每条 turn 折算后写进 {@code kb_skill_interview_import}（turn_id 唯一），
 *       重复调用只会处理新增的轮次；</li>
 *   <li><b>不让模型造节点</b>：只在目标库已有的目录里做名字匹配，匹配不到就报出来等人工补，
 *       或用 {@code createMissing} 显式建（建在统一的根节点下，便于整体删改）；</li>
 *   <li><b>先干跑</b>：{@code dryRun=true} 只返回"会匹配到哪、有多少条匹配不上"，不写库。</li>
 * </ol>
 */
@Service
public class KbInterviewBackfillService {

    private static final Logger log = LoggerFactory.getLogger(KbInterviewBackfillService.class);

    /** 面试得分（0~100）折算成观测：70 分以上算答对，40 分以上算半对，其余算答错。 */
    private static final int SCORE_CORRECT = 70;
    private static final int SCORE_UNSURE = 40;
    private static final int MAX_UNMATCHED_LISTED = 30;
    private static final int MAX_SIGNALS = 5000;

    private final KbInterviewSignalStore signals;
    private final KbSkillInterviewImportStore imports;
    private final KbSkillService skillService;
    private final KbCategoryStore categories;
    private final KbCatalogService catalog;
    private final KnowledgeBaseRepository bases;
    private final KbAccessGuard guard;

    public KbInterviewBackfillService(KbInterviewSignalStore signals,
                                      KbSkillInterviewImportStore imports,
                                      KbSkillService skillService,
                                      KbCategoryStore categories,
                                      KbCatalogService catalog,
                                      KnowledgeBaseRepository bases,
                                      KbAccessGuard guard) {
        this.signals = signals;
        this.imports = imports;
        this.skillService = skillService;
        this.categories = categories;
        this.catalog = catalog;
        this.bases = bases;
        this.guard = guard;
    }

    @Transactional
    public KbQuizViews.InterviewBackfill backfill(Long kbId, Long userId, boolean dryRun,
                                                  boolean createMissing, String rootName) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 userId：面试记录没有用户归属，必须显式指定记到谁头上");
        }
        guard.ensureReadable(bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId)));

        List<KbCategory> nodes = new ArrayList<>(categories.findByKb(kbId));
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (KbCategory node : nodes) {
            nameById.put(node.getId(), node.name());
        }

        Set<Long> done = imports.importedTurnIds(kbId);
        List<KbInterviewSignalStore.Signal> pending = new ArrayList<>();
        for (KbInterviewSignalStore.Signal signal : signals.findSignals(MAX_SIGNALS)) {
            if (!done.contains(signal.turnId())) {
                pending.add(signal);
            }
        }

        Map<Long, Long> matchedCategory = new LinkedHashMap<>();
        Set<String> unmatched = new LinkedHashSet<>();
        for (KbInterviewSignalStore.Signal signal : pending) {
            KbCategory hit = InterviewSkillMatcher.match(signal.skill(), nodes);
            if (hit != null && hit.getId() != null) {
                matchedCategory.put(signal.turnId(), hit.getId());
            } else {
                unmatched.add(signal.skill().strip());
            }
        }

        int createdCategories = 0;
        if (createMissing && !unmatched.isEmpty()) {
            Long rootId = ensureRoot(kbId, rootName);
            Map<String, Long> createdBySkill = new LinkedHashMap<>();
            for (String skill : new ArrayList<>(unmatched)) {
                Long categoryId = ensureLeaf(kbId, rootId, skill);
                if (categoryId == null) {
                    continue;
                }
                createdBySkill.put(skill, categoryId);
                nameById.put(categoryId, skill);
                unmatched.remove(skill);
            }
            createdCategories = createdBySkill.size();
            for (KbInterviewSignalStore.Signal signal : pending) {
                Long categoryId = createdBySkill.get(signal.skill().strip());
                if (categoryId != null) {
                    matchedCategory.put(signal.turnId(), categoryId);
                }
            }
        }

        Map<Long, Integer> turnsByCategory = new LinkedHashMap<>();
        int applied = 0;
        Instant now = Instant.now();
        for (KbInterviewSignalStore.Signal signal : pending) {
            Long categoryId = matchedCategory.get(signal.turnId());
            if (categoryId == null) {
                continue;
            }
            String observation = observationOf(signal.score());
            turnsByCategory.merge(categoryId, 1, Integer::sum);
            if (dryRun) {
                continue;
            }
            skillService.record(userId, kbId, categoryId, observation, signal.difficulty(), true);
            imports.save(KbSkillInterviewImport.of(signal.turnId(), userId, kbId, categoryId,
                    signal.skill(), observation, signal.score(), now));
            applied++;
        }

        List<KbQuizViews.InterviewCategory> byCategory = new ArrayList<>();
        turnsByCategory.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> byCategory.add(new KbQuizViews.InterviewCategory(
                        entry.getKey(), nameById.getOrDefault(entry.getKey(), "知识点 #" + entry.getKey()),
                        entry.getValue())));

        List<String> unmatchedListed = new ArrayList<>(unmatched);
        if (unmatchedListed.size() > MAX_UNMATCHED_LISTED) {
            unmatchedListed = unmatchedListed.subList(0, MAX_UNMATCHED_LISTED);
        }
        log.info("面试信号回填 kbId={} userId={} 待处理={} 匹配={} 应用={} 新建目录={} dryRun={}",
                kbId, userId, pending.size(), matchedCategory.size(), applied, createdCategories, dryRun);
        return new KbQuizViews.InterviewBackfill(kbId, userId, dryRun, pending.size(),
                matchedCategory.size(), applied, unmatched.size(), createdCategories,
                unmatchedListed, byCategory, imports.countByKb(kbId));
    }

    /** 面试总分 → 观测值。分数是 0~100 的整数，门槛取整避免边界抖动。 */
    static String observationOf(Integer score) {
        if (score == null) {
            return "UNSURE";
        }
        if (score >= SCORE_CORRECT) {
            return "CORRECT";
        }
        return score >= SCORE_UNSURE ? "UNSURE" : "WRONG";
    }

    private Long ensureRoot(Long kbId, String rootName) {
        String name = rootName == null || rootName.isBlank() ? "面试评估" : rootName.strip();
        for (KbCategory node : categories.findByKb(kbId)) {
            if (node.parentId() == KbCategory.ROOT_PARENT_ID && node.name().equals(name)) {
                return node.getId();
            }
        }
        return catalog.create(kbId, null, name, "面试轮次按 skill 自动归档的根节点", null).id();
    }

    private Long ensureLeaf(Long kbId, Long parentId, String skill) {
        String name = skill.length() > 64 ? skill.substring(0, 64) : skill;
        for (KbCategory node : categories.findByKb(kbId)) {
            if (node.parentId().equals(parentId) && node.name().equals(name)) {
                return node.getId();
            }
        }
        try {
            return catalog.create(kbId, parentId, name, "来自面试轮次的 skill 标签", null).id();
        } catch (BusinessException ex) {
            log.warn("建面试知识点失败 kbId={} skill={} reason={}", kbId, name, ex.getMessage());
            return null;
        }
    }
}
