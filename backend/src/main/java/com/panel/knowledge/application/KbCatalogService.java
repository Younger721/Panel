package com.panel.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbChunk;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识目录：每个 KB 一棵树，chunk / question 挂到叶子上。
 *
 * <p>P0 只做目录与手动归属；LLM 归类在 P1 接入，写入同一张 kb_item_category。</p>
 */
@Service
public class KbCatalogService {

    private static final Logger log = LoggerFactory.getLogger(KbCatalogService.class);

    public static final long UNCATEGORIZED_ID = -1L;
    private static final int MAX_PAGE = 100;
    private static final int GROUP_PREVIEW = 6;

    private final KbCategoryStore categories;
    private final KbItemCategoryStore items;
    private final KbChunkRepository chunks;
    private final KbDocumentRepository documents;
    private final KbQuestionStore questions;
    private final KnowledgeBaseRepository bases;
    private final KbAccessGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();

    public KbCatalogService(KbCategoryStore categories,
                            KbItemCategoryStore items,
                            KbChunkRepository chunks,
                            KbDocumentRepository documents,
                            KbQuestionStore questions,
                            KnowledgeBaseRepository bases,
                            KbAccessGuard guard) {
        this.categories = categories;
        this.items = items;
        this.chunks = chunks;
        this.documents = documents;
        this.questions = questions;
        this.bases = bases;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- tree

    public List<KbCatalogNode> tree(Long kbId) {
        requireBase(kbId);
        List<KbCategory> all = categories.findByKb(kbId);
        Map<Long, Set<Long>> chunkSets = new HashMap<>();
        Map<Long, Set<Long>> questionSets = new HashMap<>();
        for (KbCategory category : all) {
            chunkSets.put(category.getId(), new HashSet<>());
            questionSets.put(category.getId(), new HashSet<>());
        }
        for (KbItemCategory item : items.findAllByKb(kbId)) {
            if (item.itemType() == KbItemType.CHUNK) {
                chunkSets.computeIfAbsent(item.categoryId(), k -> new HashSet<>()).add(item.itemId());
            } else {
                questionSets.computeIfAbsent(item.categoryId(), k -> new HashSet<>()).add(item.itemId());
            }
        }

        // 自底向上把子节点集合并入父节点（按 depth 倒序即可）
        List<KbCategory> ordered = new ArrayList<>(all);
        ordered.sort(Comparator.comparingInt(KbCategory::depth));
        for (int i = ordered.size() - 1; i >= 0; i--) {
            KbCategory category = ordered.get(i);
            if (category.parentId() != null && category.parentId() > 0
                    && chunkSets.containsKey(category.parentId())) {
                chunkSets.get(category.parentId()).addAll(chunkSets.get(category.getId()));
                questionSets.get(category.parentId()).addAll(questionSets.get(category.getId()));
            }
        }

        Map<Long, List<KbCategory>> children = new LinkedHashMap<>();
        for (KbCategory category : all) {
            children.computeIfAbsent(category.parentId(), k -> new ArrayList<>()).add(category);
        }
        children.values().forEach(list -> list.sort(
                Comparator.comparingInt(KbCategory::sortNo).thenComparing(KbCategory::getId)));

        List<KbCatalogNode> roots = new ArrayList<>();
        for (KbCategory root : children.getOrDefault(KbCategory.ROOT_PARENT_ID, List.of())) {
            roots.add(toNode(root, children, chunkSets, questionSets));
        }

        Set<Long> assignedChunks = new HashSet<>();
        Set<Long> assignedQuestions = new HashSet<>();
        for (Set<Long> set : chunkSets.values()) {
            assignedChunks.addAll(set);
        }
        for (Set<Long> set : questionSets.values()) {
            assignedQuestions.addAll(set);
        }
        long totalChunks = chunks.countByKbId(kbId);
        long totalQuestions = questions.countByKb(kbId);
        long unassignedChunks = Math.max(0, totalChunks - assignedChunks.size());
        long unassignedQuestions = Math.max(0, totalQuestions - assignedQuestions.size());

        roots.add(new KbCatalogNode(UNCATEGORIZED_ID, 0L, "未分类", "__uncategorized", 1,
                unassignedChunks, unassignedQuestions, true, "尚未归入目录的切片与题目", List.of()));
        return roots;
    }

    private KbCatalogNode toNode(KbCategory category,
                                 Map<Long, List<KbCategory>> children,
                                 Map<Long, Set<Long>> chunkSets,
                                 Map<Long, Set<Long>> questionSets) {
        List<KbCatalogNode> childNodes = children.getOrDefault(category.getId(), List.of()).stream()
                .map(child -> toNode(child, children, chunkSets, questionSets))
                .toList();
        return new KbCatalogNode(
                category.getId(),
                category.parentId(),
                category.name(),
                category.pathKey(),
                category.depth(),
                chunkSets.getOrDefault(category.getId(), Set.of()).size(),
                questionSets.getOrDefault(category.getId(), Set.of()).size(),
                false,
                category.description(),
                childNodes);
    }

    // -------------------------------------------------------------- create

    @Transactional
    public KbCategoryView create(Long kbId, Long parentId, String name, String description, String requestedKey) {
        requireBase(kbId);
        String cleanName = KbCategory.requireName(name);
        KbCategory parent = parentId == null || parentId <= 0 ? null : requireCategory(kbId, parentId);
        int depth = parent == null ? 1 : parent.depth() + 1;
        if (depth > KbCategory.MAX_DEPTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目录最多 3 级");
        }
        long parentValue = parent == null ? KbCategory.ROOT_PARENT_ID : parent.getId();
        if (categories.existsSibling(kbId, parentValue, cleanName, null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同级目录下已有同名节点");
        }
        String requested = requestedKey == null ? "" : requestedKey.strip();
        if (parent != null && requested.startsWith(parent.pathKey() + "/")) {
            requested = requested.substring(parent.pathKey().length() + 1);
        }
        String segment = requested.isBlank()
                ? slug(cleanName)
                : sanitizeKey(requested);
        if (segment.isBlank()) {
            segment = slug(cleanName);
        }
        String pathKey = parent == null ? segment : parent.pathKey() + "/" + segment;
        if (categories.existsPathKey(kbId, pathKey, null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "pathKey 已存在：" + pathKey);
        }

        KbCategory category = KbCategory.create(kbId, parentValue, cleanName, description, 0);
        category = categories.insert(category);
        String path = (parent == null ? "/" : parent.path()) + category.getId() + "/";
        category.applyPath(path, pathKey, depth);
        category = categories.update(category);
        return view(category);
    }

    /**
     * 把另一个库的目录树复制过来（按 {@code path_key} 去重）。
     *
     * <p>用途：题库库的树经常是空的，自动归类无处可选，于是「答错跳知识点」整条链路失效。
     * 直接从技术知识库继承一棵已经整理好的树，比让 LLM 从零猜结构靠谱得多。</p>
     */
    @Transactional
    public Map<String, Object> importTree(Long targetKbId, Long sourceKbId) {
        requireBase(targetKbId);
        requireBase(sourceKbId);
        if (targetKbId.equals(sourceKbId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能从自己导入目录");
        }
        List<KbCategory> source = new ArrayList<>(categories.findByKb(sourceKbId));
        source.sort(Comparator.comparingInt(KbCategory::depth).thenComparing(KbCategory::getId));
        Map<String, KbCategory> targetByKey = new HashMap<>();
        for (KbCategory category : categories.findByKb(targetKbId)) {
            targetByKey.put(category.pathKey(), category);
        }
        Map<Long, Long> idMap = new HashMap<>();
        int imported = 0;
        int skipped = 0;
        for (KbCategory node : source) {
            KbCategory existing = targetByKey.get(node.pathKey());
            if (existing != null) {
                idMap.put(node.getId(), existing.getId());
                skipped++;
                continue;
            }
            Long parentId = null;
            if (node.parentId() != null && node.parentId() != KbCategory.ROOT_PARENT_ID) {
                parentId = idMap.get(node.parentId());
                if (parentId == null) {
                    // 父节点没导进来（被裁掉的系统节点之类），这个子树就跳过
                    skipped++;
                    continue;
                }
            }
            try {
                KbCategoryView created = create(targetKbId, parentId, node.name(), node.description(),
                        node.pathKey());
                idMap.put(node.getId(), created.id());
                targetByKey.put(node.pathKey(), requireCategoryById(created.id()));
                imported++;
            } catch (BusinessException exception) {
                // 同名冲突等：跳过这一枝，不拖垮整批导入
                skipped++;
            }
        }
        return Map.of("imported", imported, "skipped", skipped, "total", source.size());
    }

    // -------------------------------------------------------------- update

    @Transactional
    public KbCategoryView rename(Long categoryId, String name, String description) {
        KbCategory category = requireCategoryById(categoryId);
        String cleanName = KbCategory.requireName(name);
        if (categories.existsSibling(category.kbId(), category.parentId(), cleanName, category.getId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同级目录下已有同名节点");
        }
        String oldKey = category.pathKey();
        // 只改名时不动描述：description 传 null 视为"保持不变"，避免就地改名把描述清空
        category.rename(cleanName, description != null ? description : category.description());
        KbCategory saved = categories.update(category);
        // path_key 跟着名字走：改完把本节点和整棵子树的 key 前缀一起换掉，否则 key 和名字会长期对不上
        String newKey = rewritePathKeys(saved, oldKey);
        if (!newKey.equals(oldKey)) {
            saved = categories.update(saved);
        }
        return view(saved);
    }

    /** 重命名后修正 pathKey：本节点换尾段，子孙把祖先前缀整体替换。返回本节点新 key。 */
    private String rewritePathKeys(KbCategory node, String oldKey) {
        String parentPrefix = node.parentId() == null || node.parentId() == KbCategory.ROOT_PARENT_ID
                ? ""
                : requireCategory(node.kbId(), node.parentId()).pathKey() + "/";
        String newKey = parentPrefix + slug(node.name());
        if (newKey.equals(oldKey)) {
            return oldKey;
        }
        node.applyPath(node.path(), newKey, node.depth());
        if (oldKey == null || oldKey.isEmpty()) {
            return newKey;
        }
        for (KbCategory child : categories.findSubtree(node.kbId(), node.path())) {
            if (child.getId().equals(node.getId())) {
                continue;
            }
            String key = child.pathKey();
            if (key != null && key.startsWith(oldKey + "/")) {
                child.applyPath(child.path(), newKey + key.substring(oldKey.length()), child.depth());
                categories.update(child);
            }
        }
        return newKey;
    }

    @Transactional
    public KbCategoryView move(Long categoryId, Long newParentId) {
        return move(categoryId, newParentId, null);
    }

    /**
     * 移动目录（支持同级排序）。
     *
     * @param beforeId 插到哪个兄弟节点之前；null 表示放到末尾。拖拽时"上下挪"就是只传 beforeId、
     *                 parentId 保持原父节点，这样不会误触发整棵子树的 path/pathKey 重算。
     */
    @Transactional
    public KbCategoryView move(Long categoryId, Long newParentId, Long beforeId) {
        KbCategory category = requireCategoryById(categoryId);
        KbCategory parent = newParentId == null || newParentId <= 0
                ? null
                : requireCategory(category.kbId(), newParentId);
        if (parent != null && parent.path().startsWith(category.path())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能移动到自己的子目录下");
        }
        int depth = parent == null ? 1 : parent.depth() + 1;
        if (depth > KbCategory.MAX_DEPTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目录最多 3 级");
        }
        long newParentValue = parent == null ? KbCategory.ROOT_PARENT_ID : parent.getId();
        boolean sameParent = category.parentId() != null && category.parentId() == newParentValue;
        if (!sameParent
                && categories.existsSibling(category.kbId(), newParentValue, category.name(), category.getId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标目录下已有同名节点");
        }

        if (sameParent) {
            // 纯排序：层级和 path 不变，只改 sortNo
            reorderSiblings(category, newParentValue, beforeId);
            return view(requireCategoryById(categoryId));
        }

        String oldPath = category.path();
        String newRootPath = (parent == null ? "/" : parent.path()) + category.getId() + "/";
        String rootKey = (parent == null ? "" : parent.pathKey() + "/") + slug(category.name());
        int delta = depth - category.depth();

        List<KbCategory> subtree = categories.findSubtree(category.kbId(), oldPath);
        Map<Long, String> newKeys = new HashMap<>();
        subtree.sort(Comparator.comparingInt(KbCategory::depth));
        for (KbCategory node : subtree) {
            String newPath = node.path().replaceFirst(Pattern.quote(oldPath), Matcher.quoteReplacement(newRootPath));
            String pathKey;
            if (node.getId().equals(category.getId())) {
                pathKey = rootKey;
            } else {
                String parentKey = newKeys.get(node.parentId());
                pathKey = (parentKey == null ? rootKey : parentKey) + "/" + slug(node.name());
            }
            newKeys.put(node.getId(), pathKey);
            if (node.getId().equals(category.getId())) {
                // 子树根节点：parentId 必须一起改，否则"移出去再拖回来"会被当成同父排序
                node.applyParent(newParentValue, newPath, pathKey, node.depth() + delta);
            } else {
                node.applyPath(newPath, pathKey, node.depth() + delta);
            }
            categories.update(node);
        }
        reorderSiblings(requireCategoryById(categoryId), newParentValue, beforeId);
        return view(requireCategoryById(categoryId));
    }

    /** 按 (sortNo, id) 重排同级节点：把 moved 挪到 beforeId 之前（null = 末尾），步长 10。 */
    private void reorderSiblings(KbCategory moved, long parentValue, Long beforeId) {
        List<KbCategory> siblings = new ArrayList<>(categories.findByKb(moved.kbId()).stream()
                .filter(node -> node.parentId() != null && node.parentId() == parentValue)
                .toList());
        siblings.removeIf(node -> node.getId().equals(moved.getId()));
        int index = siblings.size();
        if (beforeId != null && beforeId > 0 && beforeId != moved.getId()) {
            for (int i = 0; i < siblings.size(); i++) {
                if (siblings.get(i).getId().equals(beforeId)) {
                    index = i;
                    break;
                }
            }
        }
        siblings.add(index, moved);
        for (int i = 0; i < siblings.size(); i++) {
            KbCategory node = siblings.get(i);
            int target = (i + 1) * 10;
            if (node.sortNo() != target) {
                node.reorder(target);
                categories.update(node);
            }
        }
    }

    @Transactional
    public void delete(Long categoryId) {
        delete(categoryId, false);
    }

    /**
     * 删除目录。
     *
     * <p>{@code cascade=false}：只允许删空节点（没有子目录、没有挂载）。
     * {@code cascade=true}：连同整棵子树一起删，子树上的题目/切片**只摘挂载**（回到"未分类"），
     * 不会删内容本体——目录是产品结构，删结构不该删资料。</p>
     */
    @Transactional
    public void delete(Long categoryId, boolean cascade) {
        KbCategory category = requireCategoryById(categoryId);
        List<KbCategory> all = categories.findByKb(category.kbId());
        if (!cascade) {
            boolean hasChildren = all.stream().anyMatch(node -> node.parentId().equals(category.getId()));
            if (hasChildren) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "先删除子目录");
            }
            if (items.existsByCategoryId(categoryId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目录下还有内容，先移动或删除条目");
            }
            categories.delete(category);
            return;
        }
        List<KbCategory> subtree = all.stream()
                .filter(node -> node.getId().equals(category.getId())
                        || (node.path() != null && node.path().startsWith(category.path())))
                .sorted(Comparator.comparingInt(KbCategory::depth).reversed())
                .toList();
        List<Long> ids = subtree.stream().map(KbCategory::getId).toList();
        int detached = ids.isEmpty() ? 0 : items.deleteByCategoryIds(ids);
        for (KbCategory node : subtree) {
            categories.delete(node);
        }
        log.info("级联删除目录 categoryId={} nodes={} detachedItems={}", categoryId, subtree.size(), detached);
    }

    // --------------------------------------------------------------- items

    public KbCatalogPage items(Long kbId, Long categoryId, String rawType, int offset, int limit) {
        requireBase(kbId);
        KbItemType type = parseType(rawType);
        int size = Math.min(MAX_PAGE, Math.max(1, limit));
        int start = Math.max(0, offset);

        if (categoryId != null && categoryId > 0) {
            KbCategory category = requireCategory(kbId, categoryId);
            List<KbCategory> children = categories.findByKb(kbId).stream()
                    .filter(node -> node.parentId().equals(category.getId()))
                    .sorted(Comparator.comparing(KbCategory::getId))
                    .toList();
            if (!children.isEmpty()) {
                return groupedPage(kbId, category, children, type, size);
            }
        }

        Set<Long> ids = resolveItemIds(kbId, categoryId, type);
        return new KbCatalogPage(loadItems(kbId, ids, type, start, size), ids.size(), start, size);
    }

    /** 父目录：返回本目录直接挂载的条目 + 一级子目录分组；子目录分组内含整棵子树的预览。 */
    private KbCatalogPage groupedPage(Long kbId, KbCategory category, List<KbCategory> children,
                                      KbItemType type, int size) {
        Set<Long> all = new HashSet<>(type == KbItemType.CHUNK
                ? chunks.findIdsByKbId(kbId)
                : questions.findIdsByKb(kbId));
        Set<Long> directIds = new HashSet<>(
                items.findItemIdsByCategory(kbId, type, Set.of(category.getId())));
        directIds.retainAll(all);
        List<KbCatalogItem> direct = loadItems(kbId, directIds, type, 0, size);

        List<KbCatalogGroup> groups = new ArrayList<>();
        for (KbCategory child : children) {
            Set<Long> childIds = resolveItemIds(kbId, child.getId(), type);
            groups.add(new KbCatalogGroup(child.getId(), child.name(), child.pathKey(),
                    childIds.size(), loadItems(kbId, childIds, type, 0, GROUP_PREVIEW)));
        }
        return new KbCatalogPage(direct, directIds.size(), 0, size, groups);
    }

    private List<KbCatalogItem> loadItems(Long kbId, Set<Long> ids, KbItemType type, int start, int size) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (type == KbItemType.CHUNK) {
            List<KbChunk> found = new ArrayList<>(chunks.findByIds(ids));
            found.sort(Comparator.comparing(KbChunk::documentId).thenComparingInt(KbChunk::ordinal));
            List<KbChunk> page = found.stream().skip(start).limit(size).toList();
            Map<Long, KbItemCategory> links = primaryLinks(kbId, type, page.stream().map(KbChunk::getId).toList());
            return page.stream()
                    .map(chunk -> toChunkItem(chunk, links.get(chunk.getId())))
                    .toList();
        }
        List<KbQuestion> found = new ArrayList<>(questions.findByIds(ids));
        found.sort(Comparator.comparing(KbQuestion::getId).reversed());
        List<KbQuestion> page = found.stream().skip(start).limit(size).toList();
        Map<Long, KbItemCategory> links = primaryLinks(kbId, type, page.stream().map(KbQuestion::getId).toList());
        return page.stream()
                .map(question -> toQuestionItem(question, links.get(question.getId())))
                .toList();
    }

    /** 每条内容取一条代表挂载：primary 优先，其次 id 最小。 */
    private Map<Long, KbItemCategory> primaryLinks(Long kbId, KbItemType type, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, KbItemCategory> result = new HashMap<>();
        for (KbItemCategory link : items.findByItemIds(kbId, type, itemIds)) {
            KbItemCategory current = result.get(link.itemId());
            if (current == null || (!current.primary() && link.primary())) {
                result.put(link.itemId(), link);
            }
        }
        return result;
    }

    private Set<Long> resolveItemIds(Long kbId, Long categoryId, KbItemType type) {
        Set<Long> all = new HashSet<>(type == KbItemType.CHUNK
                ? chunks.findIdsByKbId(kbId)
                : questions.findIdsByKb(kbId));
        Set<Long> assigned = new HashSet<>(items.findAssignedItemIds(kbId, type));

        if (categoryId == null || categoryId == UNCATEGORIZED_ID) {
            all.removeAll(assigned);
            return all;
        }
        KbCategory category = requireCategory(kbId, categoryId);
        List<Long> subtreeIds = categories.findSubtree(kbId, category.path()).stream()
                .map(KbCategory::getId)
                .toList();
        Set<Long> result = new HashSet<>(items.findItemIdsByCategory(kbId, type, subtreeIds));
        result.retainAll(all);
        return result;
    }

    @Transactional
    public void assign(Long categoryId, String rawType, Long itemId, Boolean primary) {
        KbCategory category = requireCategoryById(categoryId);
        KbItemType type = parseType(rawType);
        validateItem(category.kbId(), type, itemId);

        List<KbItemCategory> existing = items.findByItem(category.kbId(), type, itemId);
        boolean already = existing.stream().anyMatch(link -> link.categoryId().equals(categoryId));
        if (already) {
            return;
        }
        boolean makePrimary = Boolean.TRUE.equals(primary) || existing.isEmpty();
        if (makePrimary) {
            items.clearPrimary(category.kbId(), type, itemId);
        }
        items.insert(KbItemCategory.manual(category.kbId(), type, itemId, categoryId, makePrimary));
    }

    /**
     * 人工确认归属：把条目当前所有挂载标成 locked（置信度拉满、来源 MANUAL）。
     *
     * <p>用于消化覆盖率里的「低置信」队列：确认之后 {@code KbAutoClassifyService} 重跑不会覆盖它。</p>
     */
    @Transactional
    public int confirm(Long kbId, String rawType, Long itemId) {
        KbItemType type = parseType(rawType);
        validateItem(kbId, type, itemId);
        List<KbItemCategory> links = items.findByItem(kbId, type, itemId);
        if (links.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "这个条目还没挂知识点，先挂一个再确认");
        }
        for (KbItemCategory link : links) {
            link.confirm();
            items.update(link);
        }
        return links.size();
    }

    /** 批量确认：没挂载的条目直接跳过（不整批失败），返回 {confirmed, skipped}。 */
    @Transactional
    public Map<String, Object> confirmBatch(Long kbId, String rawType, List<Long> itemIds) {
        KbItemType type = parseType(rawType);
        if (itemIds == null || itemIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "itemIds 不能为空");
        }
        int confirmed = 0;
        int skipped = 0;
        for (Long itemId : itemIds.stream().filter(java.util.Objects::nonNull).distinct().limit(500).toList()) {
            List<KbItemCategory> links = items.findByItem(kbId, type, itemId);
            if (links.isEmpty()) {
                skipped++;
                continue;
            }
            for (KbItemCategory link : links) {
                link.confirm();
                items.update(link);
                confirmed++;
            }
        }
        return Map.of("confirmed", confirmed, "skipped", skipped);
    }

    /**
     * 全库一键确认：把这个库里该类型所有「自动挂的、还没人确认过」的归属钉成人工真值。
     * 已确认的跳过（不重复写），没挂载的自然也不在里面。
     */
    @Transactional
    public Map<String, Object> confirmAll(Long kbId, String rawType) {
        KbItemType type = parseType(rawType);
        requireBase(kbId);
        int confirmed = 0;
        int already = 0;
        for (KbItemCategory link : items.findAllByKb(kbId)) {
            if (link.itemType() != type) {
                continue;
            }
            if (link.locked()) {
                already++;
                continue;
            }
            link.confirm();
            items.update(link);
            confirmed++;
        }
        log.info("一键确认归属 kb={} type={} confirmed={} already={}", kbId, type, confirmed, already);
        return Map.of("confirmed", confirmed, "alreadyLocked", already);
    }

    /** 检索过滤：返回这些目录（含子树）下所有切片的 ES chunkKey。 */
    public Set<String> chunkKeysInCategories(Long kbId, Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Set.of();
        }
        List<KbCategory> all = categories.findByKb(kbId);
        Map<Long, KbCategory> byId = new HashMap<>();
        for (KbCategory category : all) {
            byId.put(category.getId(), category);
        }
        Set<Long> subtree = new HashSet<>();
        for (Long categoryId : categoryIds) {
            KbCategory root = byId.get(categoryId);
            if (root == null) {
                continue;
            }
            String prefix = root.path().endsWith("/") ? root.path() : root.path() + "/";
            for (KbCategory candidate : all) {
                if (candidate.path().equals(root.path()) || candidate.path().startsWith(prefix)) {
                    subtree.add(candidate.getId());
                }
            }
        }
        if (subtree.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = items.findItemIdsByCategory(kbId, KbItemType.CHUNK, subtree);
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (KbChunk chunk : chunks.findByIds(ids)) {
            keys.add(chunk.chunkKey());
        }
        return keys;
    }

    @Transactional
    public void moveItem(Long kbId, String rawType, Long itemId, Long targetCategoryId) {
        KbItemType type = parseType(rawType);
        validateItem(kbId, type, itemId);
        KbCategory target = null;
        if (targetCategoryId != null && targetCategoryId > 0) {
            target = requireCategory(kbId, targetCategoryId);
        }
        final Long targetId = target == null ? null : target.getId();
        List<KbItemCategory> links = items.findByItem(kbId, type, itemId);
        // 目标就是当前挂着的那个节点时，不能"先删再插"：唯一键 (kb, type, item, category) 会撞。
        KbItemCategory kept = targetId == null ? null : links.stream()
                .filter(link -> link.categoryId().equals(targetId))
                .findFirst()
                .orElse(null);
        for (KbItemCategory link : links) {
            if (link == kept) {
                continue;
            }
            items.delete(link);
        }
        if (kept != null) {
            kept.setPrimary(true);
            kept.confirm();
            items.update(kept);
        } else if (target != null) {
            items.insert(KbItemCategory.manual(kbId, type, itemId, target.getId(), true));
        }
    }

    @Transactional
    public void unassign(Long categoryId, String rawType, Long itemId) {
        KbCategory category = requireCategoryById(categoryId);
        KbItemType type = parseType(rawType);
        List<KbItemCategory> links = items.findByItem(category.kbId(), type, itemId);
        KbItemCategory target = links.stream()
                .filter(link -> link.categoryId().equals(categoryId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return;
        }
        boolean wasPrimary = target.primary();
        items.delete(target);
        if (wasPrimary) {
            List<KbItemCategory> rest = items.findByItem(category.kbId(), type, itemId);
            if (!rest.isEmpty()) {
                KbItemCategory next = rest.get(0);
                next.setPrimary(true);
                items.update(next);
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private void validateItem(Long kbId, KbItemType type, Long itemId) {
        if (itemId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 itemId");
        }
        if (type == KbItemType.CHUNK) {
            List<KbChunk> found = chunks.findByIds(List.of(itemId));
            if (found.isEmpty() || !kbId.equals(found.get(0).kbId())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "切片不存在：" + itemId);
            }
            return;
        }
        KbQuestion question = questions.findById(itemId);
        if (question == null || !kbId.equals(question.kbId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "题目不存在：" + itemId);
        }
    }

    private KbCatalogItem toChunkItem(KbChunk chunk, KbItemCategory link) {
        String documentName = documents.findById(chunk.documentId())
                .map(KbDocument::fileName)
                .orElse("未知文档");
        String title = chunk.heading() == null || chunk.heading().isBlank()
                ? "切片 #" + chunk.ordinal()
                : chunk.heading();
        return new KbCatalogItem(chunk.getId(), "CHUNK", title, excerpt(chunk.text()),
                chunk.documentId(), documentName, chunk.ordinal(), chunk.pageNo(),
                null, null, null, null, List.of(),
                link == null ? null : link.categoryId(),
                link != null && link.locked(),
                link == null ? null : link.confidence(),
                link == null ? null : link.source());
    }

    private KbCatalogItem toQuestionItem(KbQuestion question, KbItemCategory link) {
        String documentName = documents.findById(question.documentId())
                .map(KbDocument::fileName)
                .orElse("未知文档");
        return new KbCatalogItem(question.getId(), "QUESTION", question.stem(),
                excerpt(question.answer()), question.documentId(), documentName, null, null,
                question.getId(), question.company(), question.year(), question.difficulty(),
                parseTags(question.tagsJson()),
                link == null ? null : link.categoryId(),
                link != null && link.locked(),
                link == null ? null : link.confidence(),
                link == null ? null : link.source());
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(tagsJson, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 220 ? flat : flat.substring(0, 220) + "…";
    }

    private static KbItemType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return KbItemType.CHUNK;
        }
        try {
            return KbItemType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的条目类型：" + raw);
        }
    }

    private static String slug(String name) {
        String value = name == null ? "" : name.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s/]+", "-")
                .replaceAll("[^\\p{IsHan}a-z0-9-]", "");
        return value.isBlank() ? "node" : value;
    }

    private static String sanitizeKey(String raw) {
        String value = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9/_-]", "");
        return value.isBlank() ? "node" : value;
    }

    private KbCategory requireCategoryById(Long categoryId) {
        KbCategory category = categories.findById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "目录不存在：" + categoryId);
        }
        return category;
    }

    private KbCategory requireCategory(Long kbId, Long categoryId) {
        KbCategory category = requireCategoryById(categoryId);
        if (!kbId.equals(category.kbId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "目录不属于该知识库");
        }
        return category;
    }

    private void requireBase(Long kbId) {
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId));
        if (base.getId() == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId);
        }
        guard.ensureReadable(base);
    }

    private KbCategoryView view(KbCategory category) {
        return new KbCategoryView(category.getId(), category.parentId(), category.name(),
                category.pathKey(), category.path(), category.depth(), category.description());
    }
}
