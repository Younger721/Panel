package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbDocument;
import com.panel.knowledge.domain.model.KbItemCategory;
import com.panel.knowledge.domain.model.KbItemType;
import com.panel.knowledge.domain.model.KbQuestion;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.persistence.KbCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbItemCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目录覆盖率：决定「答错 → 跳知识点」这条链路到底通不通。
 *
 * <p>口径：一道题只要有一条 {@code kb_item_category} 归属就算已挂；置信度低于自动归类阈值
 * （0.72）且未人工锁定的，算“低置信”，要进确认队列——这类题在 UI 上能跳，但可能跳错。</p>
 */
@Service
public class KbCatalogCoverageService {

    /** 与 {@link KbAutoClassifyService} 的自动采纳阈值保持一致。 */
    private static final double LOW_CONFIDENCE = 0.72;
    private static final int MAX_GAPS = 20;

    private final KbQuestionStore questions;
    private final KbItemCategoryStore items;
    private final KbCategoryStore categories;
    private final KbDocumentRepository documents;
    private final KnowledgeBaseRepository bases;
    private final KbAccessGuard guard;

    public KbCatalogCoverageService(KbQuestionStore questions,
                                    KbItemCategoryStore items,
                                    KbCategoryStore categories,
                                    KbDocumentRepository documents,
                                    KnowledgeBaseRepository bases,
                                    KbAccessGuard guard) {
        this.questions = questions;
        this.items = items;
        this.categories = categories;
        this.documents = documents;
        this.bases = bases;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public KbQuizViews.Coverage coverage(Long kbId) {
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId));
        guard.ensureReadable(base);
        List<Long> questionIds = questions.findIdsByKb(kbId);
        Set<Long> pool = new HashSet<>(questionIds);

        Map<Long, KbItemCategory> primary = new HashMap<>();
        Set<Long> assigned = new HashSet<>();
        for (KbItemCategory link : items.findAllByKb(kbId)) {
            if (link.itemType() != KbItemType.QUESTION || !pool.contains(link.itemId())) {
                continue;
            }
            assigned.add(link.itemId());
            KbItemCategory current = primary.get(link.itemId());
            if (current == null || (!current.primary() && link.primary())) {
                primary.put(link.itemId(), link);
            }
        }

        List<KbItemCategory> lowConfidence = primary.values().stream()
                .filter(link -> !link.locked() && link.confidence() != null
                        && link.confidence() < LOW_CONFIDENCE)
                .toList();

        List<KbCategory> all = categories.findByKb(kbId);
        Map<Long, String> names = new HashMap<>();
        Set<Long> parents = new HashSet<>();
        for (KbCategory category : all) {
            names.put(category.getId(), category.name());
            if (category.parentId() != null && category.parentId() != KbCategory.ROOT_PARENT_ID) {
                parents.add(category.parentId());
            }
        }
        long leaves = all.stream().filter(category -> !parents.contains(category.getId())).count();

        // 缺口队列：低置信（跳了可能跳错）排在前面，未挂任何知识点（跳都跳不了）跟在后面。
        List<Long> uncertainIds = lowConfidence.stream().map(KbItemCategory::itemId).toList();
        List<Long> uncategorizedIds = questionIds.stream()
                .filter(id -> !assigned.contains(id))
                .limit(MAX_GAPS)
                .toList();
        List<Long> gapIds = new ArrayList<>(uncertainIds);
        gapIds.addAll(uncategorizedIds);
        Map<Long, KbQuestion> byId = new HashMap<>();
        if (!gapIds.isEmpty()) {
            for (KbQuestion question : questions.findByIds(gapIds)) {
                byId.put(question.getId(), question);
            }
        }

        List<KbQuizViews.Gap> gaps = new ArrayList<>();
        for (KbItemCategory link : lowConfidence) {
            if (gaps.size() >= MAX_GAPS) {
                break;
            }
            KbQuestion question = byId.get(link.itemId());
            if (question == null) {
                continue;
            }
            gaps.add(new KbQuizViews.Gap(question.getId(), question.stem(), link.categoryId(),
                    names.get(link.categoryId()), link.confidence()));
        }
        for (Long id : uncategorizedIds) {
            if (gaps.size() >= MAX_GAPS) {
                break;
            }
            KbQuestion question = byId.get(id);
            if (question == null) {
                continue;
            }
            gaps.add(new KbQuizViews.Gap(question.getId(), question.stem(), null, null, null));
        }

        long total = questionIds.size();
        // 题库是空的就报 0，别说 100%：没有题目谈不上"覆盖"，前端也会因此不提示缺口
        double ratio = total == 0 ? 0d : Math.round(assigned.size() * 10_000d / total) / 10_000d;
        return new KbQuizViews.Coverage(kbId, total, assigned.size(), Math.max(0, total - assigned.size()),
                lowConfidence.size(), all.size(), leaves, ratio, gaps, documentGaps(kbId, assigned, uncertainIds));
    }

    /** 按文档聚合缺口：一份题库文档刚导进来时，这张表最能说明"先补哪份"。 */
    private List<KbQuizViews.DocumentGap> documentGaps(Long kbId, Set<Long> assigned, List<Long> lowConfidenceIds) {
        List<KbQuestion> all = questions.findAllByKb(kbId);
        if (all.isEmpty()) {
            return List.of();
        }
        Set<Long> uncertain = new HashSet<>(lowConfidenceIds);
        Map<Long, long[]> perDocument = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (KbQuestion question : all) {
            long[] counters = perDocument.computeIfAbsent(question.documentId(), ignored -> new long[3]);
            counters[0]++;
            if (assigned.contains(question.getId())) {
                counters[1]++;
            }
            if (uncertain.contains(question.getId())) {
                counters[2]++;
            }
        }
        for (KbDocument document : documents.findAllByKbId(kbId)) {
            names.put(document.getId(), document.fileName());
        }
        List<KbQuizViews.DocumentGap> result = new ArrayList<>(perDocument.size());
        for (Map.Entry<Long, long[]> entry : perDocument.entrySet()) {
            long[] counters = entry.getValue();
            long unassigned = counters[0] - counters[1];
            if (unassigned == 0 && counters[2] == 0) {
                continue; // 已经全挂且没有待确认的文档不进缺口表
            }
            result.add(new KbQuizViews.DocumentGap(entry.getKey(),
                    names.getOrDefault(entry.getKey(), "文档 #" + entry.getKey()),
                    counters[0], counters[1], counters[2]));
        }
        result.sort((left, right) -> Long.compare(right.uncategorized(), left.uncategorized()));
        return result;
    }

    /** 供其它服务复用：这个库是不是题库（覆盖度看板只对题库有意义）。 */
    public KnowledgeBase requireBase(Long kbId) {
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId));
        guard.ensureReadable(base);
        return base;
    }
}
