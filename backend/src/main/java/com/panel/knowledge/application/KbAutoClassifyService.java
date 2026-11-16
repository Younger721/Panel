package com.panel.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbChunk;
import com.panel.knowledge.domain.model.KbItemCategory;
import com.panel.knowledge.domain.model.KbItemType;
import com.panel.knowledge.domain.model.KbQuestion;
import com.panel.knowledge.infrastructure.persistence.KbCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbItemCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.shared.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传后的自动归类：优先让 LLM 在已有目录里选，失败或低置信度时退回关键词规则。
 *
 * <p>只处理“未归属”的切片/题目；人工挂过的条目不动。置信度 &gt;= {@link #AUTO_APPLY}
 * 才落库，低于阈值留到未分类，等待人工处理。</p>
 */
@Service
public class KbAutoClassifyService {

    private static final Logger log = LoggerFactory.getLogger(KbAutoClassifyService.class);
    private static final double AUTO_APPLY = 0.72;
    private static final int MAX_ITEMS_PER_RUN = 500;
    private static final int BATCH_SIZE = 10;
    private static final int TEXT_LIMIT = 360;

    private final KbCategoryStore categories;
    private final KbItemCategoryStore links;
    private final KbChunkRepository chunks;
    private final KbQuestionStore questions;
    private final ChatModelGateway chat;
    private final KbCatalogService catalog;
    private final ObjectMapper mapper = new ObjectMapper();

    public KbAutoClassifyService(KbCategoryStore categories,
                                 KbItemCategoryStore links,
                                 KbChunkRepository chunks,
                                 KbQuestionStore questions,
                                 ChatModelGateway chat,
                                 KbCatalogService catalog) {
        this.categories = categories;
        this.links = links;
        this.chunks = chunks;
        this.questions = questions;
        this.chat = chat;
        this.catalog = catalog;
    }

    public KbClassifyResult classifyDocument(Long kbId, Long documentId) {
        return classifyDocument(kbId, documentId, false);
    }

    /**
     * 按文档一键归类。
     *
     * @param redoLowConfidence 先把该文档里"未锁定且置信度低于阈值"的挂载清掉再重跑。
     *                          低置信建议本来就是模型没把握的结果，不给它一次重来的机会，
     *                          覆盖率看板上的缺口只能靠人工一条条点。
     */
    /**
     * 切片变成待归类条目时，标题要带上它所属的那道题。
     *
     * <p>一篇面试题文档切出来的块，标题常常只有「简短回答」「详细解析」「示例」。
     * 这种词对归类毫无信息量，模型只能瞎猜——实测把「MyBatis 中 # 和 $ 的区别」下面的
     * 「简短回答」挂到了「Agent / 工具调用 / 工具定义与参数校验」。
     * 带上题目名之后，锚词能命中，模型也有上下文可依。</p>
     */
    private List<ItemRef> chunkItemsOf(List<KbChunk> ordered) {
        List<ItemRef> items = new ArrayList<>(ordered.size());
        String section = "";
        Long currentDocument = null;
        for (KbChunk chunk : ordered) {
            if (currentDocument != null && !currentDocument.equals(chunk.documentId())) {
                section = "";
            }
            currentDocument = chunk.documentId();
            String heading = safe(chunk.heading());
            if (heading.matches("^\\d+[.、)）].*") || heading.contains("？") || heading.contains("?")) {
                section = heading;
            }
            String title = section.isBlank() || section.equals(heading) ? heading : section + " · " + heading;
            items.add(new ItemRef(chunk.getId(), title, safe(chunk.text()), KbItemType.CHUNK, chunk.documentId()));
        }
        return items;
    }

    @Transactional
    public KbClassifyResult classifyDocument(Long kbId, Long documentId, boolean redoLowConfidence) {
        List<ItemRef> questionItems = questions.findByDocumentId(documentId).stream()
                .map(question -> new ItemRef(question.getId(), safe(question.stem()),
                        safe(question.answer()), KbItemType.QUESTION, question.documentId()))
                .toList();
        // 题库只按题目建目录；chunk 是来源文档结构，混进来会把树污染成“数据库/MySQL”这种文档目录。
        // 非题库（技术文档/八股）没有题目，才按切片归类。
        List<ItemRef> chunkItems = questionItems.isEmpty()
                ? chunkItemsOf(chunks.findByDocumentIdOrderByOrdinal(documentId))
                : List.of();
        int cleared = redoLowConfidence ? clearLowConfidence(kbId, chunkItems, questionItems) : 0;
        return classify(kbId, chunkItems, questionItems, cleared);
    }

    /** 清掉"模型没把握"的建议挂载；人工确认过的（locked）一律不动。 */
    private int clearLowConfidence(Long kbId, List<ItemRef> chunkItems, List<ItemRef> questionItems) {
        int cleared = 0;
        for (ItemRef item : concat(chunkItems, questionItems)) {
            for (KbItemCategory link : links.findByItem(kbId, item.type(), item.id())) {
                if (link.locked()) {
                    continue;
                }
                Double confidence = link.confidence();
                if (confidence != null && confidence < AUTO_APPLY) {
                    links.delete(link);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    private static List<ItemRef> concat(List<ItemRef> left, List<ItemRef> right) {
        List<ItemRef> all = new ArrayList<>(left.size() + right.size());
        all.addAll(left);
        all.addAll(right);
        return all;
    }

    /** 手动触发：把当前所有未分类内容重新跑一遍。 */
    public KbClassifyResult classifyUncategorized(Long kbId) {
        Set<Long> assignedChunks = new HashSet<>(links.findAssignedItemIds(kbId, KbItemType.CHUNK));
        Set<Long> assignedQuestions = new HashSet<>(links.findAssignedItemIds(kbId, KbItemType.QUESTION));
        List<ItemRef> chunkItems = chunkItemsOf(chunks.findIdsByKbId(kbId).stream()
                .map(id -> chunks.findByIds(List.of(id)))
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .sorted(Comparator.comparing(KbChunk::documentId).thenComparingInt(KbChunk::ordinal))
                .toList()).stream()
                .filter(item -> !assignedChunks.contains(item.id()))
                .limit(MAX_ITEMS_PER_RUN)
                .toList();
        List<ItemRef> questionItems = questions.findIdsByKb(kbId).stream()
                .filter(id -> !assignedQuestions.contains(id))
                .limit(MAX_ITEMS_PER_RUN)
                .map(questions::findById)
                .filter(question -> question != null)
                .map(question -> new ItemRef(question.getId(), safe(question.stem()),
                        safe(question.answer()), KbItemType.QUESTION, question.documentId()))
                .toList();
        return classify(kbId, chunkItems, questionItems);
    }

    private KbClassifyResult classify(Long kbId, List<ItemRef> chunkItems, List<ItemRef> questionItems) {
        return classify(kbId, chunkItems, questionItems, 0);
    }

    private KbClassifyResult classify(Long kbId, List<ItemRef> chunkItems, List<ItemRef> questionItems,
                                      int cleared) {
        List<KbCategory> nodes = categories.findByKb(kbId);
        Map<Long, KbCategory> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.getId(), node));
        List<KbCategory> leaves = nodes.stream()
                .filter(node -> nodes.stream().noneMatch(other -> node.getId().equals(other.parentId())))
                .sorted(Comparator.comparingInt(KbCategory::depth).reversed())
                .toList();
        List<KbCategory> candidates = leaves.isEmpty() ? nodes : leaves;

        int llmApplied = 0;
        int ruleApplied = 0;
        Set<Long> assigned = new HashSet<>();
        assigned.addAll(links.findAssignedItemIds(kbId, KbItemType.CHUNK));
        assigned.addAll(links.findAssignedItemIds(kbId, KbItemType.QUESTION));
        // 跑之前拍快照：报告按"前后差集"算，不数过程
        Set<Long> before = new HashSet<>(assigned);

        llmApplied += runLlm(kbId, chunkItems, candidates, byId, assigned);
        llmApplied += runLlm(kbId, questionItems, candidates, byId, assigned);
        ruleApplied += runRules(kbId, chunkItems, candidates, assigned);
        ruleApplied += runRules(kbId, questionItems, candidates, assigned);

        int total = chunkItems.size() + questionItems.size();
        String mode = llmApplied > 0 ? "LLM" : ruleApplied > 0 ? "RULE" : "NONE";

        // 只读事务不会 flush：不先落库，下面的快照读不到刚挂上去的行，报告会少算
        links.flushPending();
        Set<Long> after = new HashSet<>(links.findAssignedItemIds(kbId, KbItemType.CHUNK));
        after.addAll(links.findAssignedItemIds(kbId, KbItemType.QUESTION));
        after.retainAll(itemIdsOf(chunkItems, questionItems));
        before.retainAll(itemIdsOf(chunkItems, questionItems));

        Set<Long> changed = new HashSet<>(after);
        changed.removeAll(before);
        Map<Long, Boolean> locked = new HashMap<>();
        locked.putAll(links.snapshotLocked(kbId, KbItemType.CHUNK));
        locked.putAll(links.snapshotLocked(kbId, KbItemType.QUESTION));
        // 已确认 / 待确认按**整个库**算：这次没东西可挂时，也要让人看到"库里那 8 条是稳的"
        int alreadyPinned = 0;
        int pending = 0;
        for (Boolean pinned : locked.values()) {
            if (Boolean.TRUE.equals(pinned)) {
                alreadyPinned++;
            } else {
                pending++;
            }
        }
        int failed = Math.max(0, total - after.size());
        log.info("自动归类完成 kb={} chunks={} questions={} 新挂={} 待确认={} 已确认={} 未分类={} cleared={}",
                kbId, chunkItems.size(), questionItems.size(), changed.size(), pending, alreadyPinned, failed, cleared);
        return new KbClassifyResult(total, changed.size(), alreadyPinned, pending, failed, mode, cleared);
    }

    /** 本次参与归类的条目 id（chunk 与 question 可能撞号，这里只用于过滤，不区分类型足够）。 */
    private static Set<Long> itemIdsOf(List<ItemRef> chunkItems, List<ItemRef> questionItems) {
        Set<Long> ids = new HashSet<>();
        chunkItems.forEach(item -> ids.add(item.id()));
        questionItems.forEach(item -> ids.add(item.id()));
        return ids;
    }

    /**
     * 第一阶段：先只判断"属于哪个主题"。
     *
     * <p>181 个叶子一次选一个，是之前错分的主因：模型在几十个不相干的名字之间，
     * 抓到哪个词面像就选哪个（Memory → 内存结构）。先缩到 30 个主题，
     * 再在某个主题的 5~10 个叶子里挑——两次小选择比一次大选择好做得多。</p>
     *
     * <p>主题词表能直接命中的不必问模型，确定性优先。</p>
     */
    private Map<Long, Long> resolveDomains(Long kbId, List<ItemRef> batch, List<KbCategory> nodes,
                                           Map<String, Long> sectionDomain) {
        List<KbCategory> domains = nodes.stream()
                .filter(node -> node.depth() == 2)
                .toList();
        if (domains.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> resolved = new HashMap<>();
        List<ItemRef> needModel = new ArrayList<>();
        for (ItemRef item : batch) {
            Long byAnchor = domainByAnchor(item, domains);
            if (byAnchor != null) {
                resolved.put(item.id(), byAnchor);
            } else {
                needModel.add(item);
            }
        }
        if (!needModel.isEmpty()) {
            Map<Long, Proposal> picked = callModel(kbId, needModel, domains);
            for (ItemRef item : needModel) {
                Proposal proposal = picked.get(item.id());
                if (proposal == null || proposal.categoryId() == null) {
                    continue;
                }
                if (domains.stream().noneMatch(node -> node.getId().equals(proposal.categoryId()))) {
                    continue;
                }
                resolved.put(item.id(), proposal.categoryId());
            }
        }
        // 还是没定下来的，跟着同文档里已经定下来的兄弟走：一篇文档通常只讲一个主题，
        // 「深入回答补充」这种块放在哪里都不违和，但跟错主题就是硬伤。
        for (ItemRef item : batch) {
            if (resolved.containsKey(item.id())) {
                continue;
            }
            Long sibling = batch.stream()
                    .filter(other -> !other.id().equals(item.id()))
                    .filter(other -> item.documentId() != null && item.documentId().equals(other.documentId()))
                    .map(other -> resolved.get(other.id()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (sibling != null) {
                resolved.put(item.id(), sibling);
            }
        }
        // 同章节的兄弟可能已经在上一个批次里定过了，跨批靠章节表兜底
        if (sectionDomain != null && !sectionDomain.isEmpty()) {
            for (ItemRef item : batch) {
                if (resolved.containsKey(item.id())) {
                    continue;
                }
                Long remembered = sectionDomain.get(sectionKey(item));
                if (remembered != null) {
                    resolved.put(item.id(), remembered);
                }
            }
        }
        return resolved;
    }

    /**
     * 主题词表命中时直接锁定主题，不问模型。
     *
     * <p>标题里的词算三分，正文里的算一分：一道「消息队列如何确保消息不丢失？」的答案里
     * 常拿 Redis 的 AOF 作反例，若正文与标题同权，Redis 会靠两个词的堆叠赢走整道题。</p>
     */
    Long domainByAnchor(ItemRef item, List<KbCategory> domains) {
        String title = item.title() == null ? "" : item.title().toLowerCase();
        String body = item.body() == null ? "" : item.body().toLowerCase();
        String bestDomain = null;
        int bestHits = 0;
        for (Map.Entry<String, List<String>> entry : TOPIC_ANCHORS.entrySet()) {
            int hits = 0;
            for (String anchor : entry.getValue()) {
                if (title.contains(anchor)) {
                    hits += 3;
                } else if (body.contains(anchor)) {
                    hits += 1;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                bestDomain = entry.getKey();
            }
        }
        if (bestDomain == null) {
            return null;
        }
        String domain = compact(bestDomain);
        return domains.stream()
                .filter(node -> node.name() != null && compact(node.name()).contains(domain))
                .map(KbCategory::getId)
                .findFirst()
                .orElse(null);
    }

    /** 去掉大小写和空格再比："java语言" 要能对上目录名「Java 语言」。 */
    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    /** 第二阶段候选：主题下面的叶子（主题自己没有子节点时，它自己就是叶子）。 */
    private List<KbCategory> leavesUnder(KbCategory domain, List<KbCategory> nodes) {
        List<KbCategory> children = nodes.stream()
                .filter(node -> domain.getId().equals(node.parentId()))
                .toList();
        return children.isEmpty() ? List.of(domain) : children;
    }

    private int runLlm(Long kbId, List<ItemRef> items, List<KbCategory> candidates,
                       Map<Long, KbCategory> byId, Set<Long> assigned) {
        List<ItemRef> pending = items.stream()
                .filter(item -> !assigned.contains(item.id()))
                .limit(MAX_ITEMS_PER_RUN)
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }
        int applied = 0;
        // 章节 → 主题的跨批记忆：一篇文档的第 20 个切片要不要跟着第 19 个走，
        // 取决于第 19 个落在哪里，而它们常常正好被 10 条的批边界劈开。
        // 定主题仍按 10 条送模型（一次 47 条会让模型质量明显下降），跨批靠这张表兜底。
        Map<String, Long> sectionDomain = new HashMap<>();
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            List<ItemRef> batch = pending.subList(start, Math.min(pending.size(), start + BATCH_SIZE));
            // 每批重新读目录：上一批刚建的目录要进入下一批候选，避免同一主题反复建节点。
            List<KbCategory> currentNodes = categories.findByKb(kbId);
            Map<Long, KbCategory> currentById = new HashMap<>();
            currentNodes.forEach(node -> currentById.put(node.getId(), node));
            List<KbCategory> currentLeaves = currentNodes.stream()
                    .filter(node -> currentNodes.stream().noneMatch(other -> node.getId().equals(other.parentId())))
                    .sorted(Comparator.comparingInt(KbCategory::depth).reversed())
                    .toList();
            List<KbCategory> currentCandidates = currentLeaves.isEmpty() ? currentNodes : currentLeaves;

            // 第一段定主题，第二段在主题内挑叶子
            Map<Long, Long> domains = resolveDomains(kbId, batch, currentNodes, sectionDomain);
            Map<Long, List<ItemRef>> groups = new LinkedHashMap<>();
            for (ItemRef item : batch) {
                Long domainId = domains.get(item.id());
                groups.computeIfAbsent(domainId == null ? -1L : domainId, ignored -> new ArrayList<>()).add(item);
            }

            for (Map.Entry<Long, List<ItemRef>> entry : groups.entrySet()) {
                KbCategory domain = entry.getKey() == -1L ? null : currentById.get(entry.getKey());
                List<KbCategory> pool = domain == null ? currentCandidates : leavesUnder(domain, currentNodes);
                // 主题已定，这里只允许在主题内的叶子里选，不许新建（否则会污染根节点）
                Map<Long, Proposal> proposals = callModel(kbId, entry.getValue(), pool, domain == null);
                for (ItemRef item : entry.getValue()) {
                    Proposal proposal = proposals.get(item.id());
                    if (proposal == null || proposal.confidence() < AUTO_APPLY) {
                        continue;
                    }
                    KbCategory category = proposal.categoryId() == null ? null : currentById.get(proposal.categoryId());
                    if (category == null && proposal.path() != null) {
                        category = ensureCategory(kbId, proposal.path());
                    }
                    if (category == null) {
                        continue;
                    }
                    double guarded = guardConfidence(item, category.pathKey(), proposal.confidence());
                    if (guarded < AUTO_APPLY) {
                        continue;
                    }
                    if (!apply(kbId, item, category.getId(), guarded)) {
                        continue;
                    }
                    assigned.add(item.id());
                    applied++;
                }
            }
            // 本批定下来的主题记进章节表，后面批次的同章节切片可以直接继承
            for (ItemRef item : batch) {
                Long domainId = domains.get(item.id());
                String key = sectionKey(item);
                if (domainId != null && !key.isEmpty()) {
                    sectionDomain.putIfAbsent(key, domainId);
                }
            }
        }
        return applied;
    }

    /** 章节键：同一篇文档 + 同一个题目小节。切片标题形如「5. Java 数组常用方法 · 数组」。 */
    private static String sectionKey(ItemRef item) {
        String title = item.title() == null ? "" : item.title();
        int cut = title.indexOf(" · ");
        if (cut <= 0) {
            return "";
        }
        return (item.documentId() == null ? "-" : item.documentId().toString()) + "|" + title.substring(0, cut);
    }

    private int runRules(Long kbId, List<ItemRef> items, List<KbCategory> candidates, Set<Long> assigned) {
        int applied = 0;
        for (ItemRef item : items) {
            if (assigned.contains(item.id())) {
                continue;
            }
            Match match = bestKeywordMatch(item, candidates);
            if (match == null) {
                continue;
            }
            KbCategory matched = candidates.stream()
                    .filter(node -> node.getId().equals(match.categoryId()))
                    .findFirst()
                    .orElse(null);
            double guarded = guardConfidence(item, matched == null ? null : matched.pathKey(), match.score());
            if (guarded < AUTO_APPLY) {
                continue;
            }
            if (!apply(kbId, item, match.categoryId(), guarded)) {
                continue;
            }
            assigned.add(item.id());
            applied++;
        }
        return applied;
    }

    /**
     * 落一条归属。两条规矩：
     * <ol>
     *   <li>人工确认过的归属（locked）一律不动——人已经表态了，机器不该再插一条；</li>
     *   <li>一个条目只留一条归属。老实现只清 primaryFlag 不删旧行，结果同一道题能同时躺在
     *       「索引与 B+ 树」和「存储引擎」两个目录下，人能看懵，覆盖率也重复计数。</li>
     * </ol>
     *
     * @return 是否真的写进去了
     */
    private boolean apply(Long kbId, ItemRef item, Long categoryId, double confidence) {
        List<KbItemCategory> existing = links.findByItem(kbId, item.type(), item.id());
        if (existing.stream().anyMatch(KbItemCategory::locked)) {
            log.info("跳过自动归属：条目已被人工确认 kb={} type={} item={} 现有={} 条",
                    kbId, item.type(), item.id(), existing.size());
            return false;
        }
        existing.forEach(links::delete);
        links.insert(KbItemCategory.suggested(kbId, item.type(), item.id(), categoryId, confidence, true));
        return true;
    }

    /**
     * 域锚词：题目里出现这些词，就说明它属于那个主题，归属路径必须落在这个主题下面。
     *
     * <p>用来兜住模型的字面误判——「数据库的存储引擎有哪些？」的答案里写着 MySQL 的
     * <code>Memory</code> 引擎，模型会把它当成 JVM 的"内存"，于是挂到
     * 「JVM / 内存结构与对象布局」。同一道题隔几分钟再问模型又能答对，
     * 说明这类错是采样波动，不能只靠提示词修，得有一条确定性的底线。</p>
     */
    private static final Map<String, List<String>> TOPIC_ANCHORS = Map.of(
            "mysql", List.of("mysql", "innodb", "myisam", "b+树", "b+ 树", "索引失效", "慢查询",
                    "存储引擎", "事务隔离", "mvcc", "回表", "执行计划", "分库分表",
                    "varchar", "char 类型", "字符集", "utf8", "范式"),
            "spring", List.of("spring", "ioc", "bean", "aop", "mybatis", "拦截器",
                    "自动装配", "事务传播", "循环依赖", "切面"),
            "java语言", List.of("java 语言", "java语言", "java ", "arraylist", "hashmap", "collections",
                    "泛型", "反射", "stream", "lambda", "string 是", "包装类", "自动装箱"),
            "redis", List.of("redis", "aof", "rdb", "缓存击穿", "缓存雪崩", "缓存穿透",
                    "哨兵", "集群槽", "跳表"),
            "jvm", List.of("jvm", "垃圾回收", "gc ", "类加载", "堆内存", "虚拟机栈",
                    "对象头", "逃逸分析", "内存屏障", "cms", "g1 ", "zgc", "full gc",
                    "minor gc", "内存泄漏"),
            "消息队列", List.of("kafka", "rocketmq", "rabbitmq", "消息队列", "消费者组", "消息堆积"),
            "并发编程", List.of("线程池", "aqs", "synchronized", "volatile", "cas", "reentrantlock", "threadlocal"),
            "agent", List.of("agent", "大模型", "llm", "prompt", "rag", "向量检索", "工具调用",
                    "function call", "沙箱", "上下文窗口", "token 预算", "智能体", "多轮对话"),
            "网络", List.of("http", "https", "tcp", "udp", "三次握手", "四次挥手", "dns", "断点续传",
                    "range 请求", "websocket", "长连接", "keep-alive"),
            "分布式与微服务", List.of("微服务", "分布式事务", "注册中心", "限流", "熔断", "降级",
                    "链路追踪", "配置中心", "分布式 id", "一致性哈希", "cap")
    );

    /**
     * 看着对不上就打折：命中了锚词、但归属路径一个都不沾，说明模型串域了。
     * 打对折后通常低于 {@link #AUTO_APPLY}，这条就留在未分类等人看，而不是被自动挂错。
     */
    double guardConfidence(ItemRef item, String pathKey, double confidence) {
        String text = (item.title() + "\n" + item.body()).toLowerCase();
        String path = pathKey == null ? "" : pathKey.toLowerCase();
        boolean anchored = false;
        boolean pathAgrees = false;
        for (Map.Entry<String, List<String>> entry : TOPIC_ANCHORS.entrySet()) {
            boolean hit = entry.getValue().stream().anyMatch(text::contains);
            if (!hit) {
                continue;
            }
            anchored = true;
            if (path.contains(entry.getKey())) {
                pathAgrees = true;
                break;
            }
        }
        if (!anchored || pathAgrees) {
            return confidence;
        }
        log.info("归属与题目主题不一致，打折：item={} path={} {} -> {}",
                item.id(), pathKey, confidence, confidence / 2);
        return confidence / 2;
    }

    private Map<Long, Proposal> callModel(Long kbId, List<ItemRef> batch, List<KbCategory> candidates) {
        return callModel(kbId, batch, candidates, true);
    }

    /**
     * @param allowNewPath 允许模型新建目录。第二阶段（主题内挑叶子）必须关掉：
     *                     主题已经定了，模型这会儿看到的候选只有五六条，它一"觉得都不像"就会
     *                     造一个 path，而 path 是从根节点开始拼的——于是库里凭空多出
     *                     「网络」「数据库」「消息队列」这类平行根节点。
     */
    private Map<Long, Proposal> callModel(Long kbId, List<ItemRef> batch, List<KbCategory> candidates,
                                          boolean allowNewPath) {
        String prompt = buildPrompt(batch, candidates, allowNewPath);
        try {
            ChatModelCallResult result = chat.completeStructuredJson(prompt, List.of());
            return parseProposals(result.content());
        } catch (RuntimeException ex) {
            log.warn("自动归类 LLM 调用失败，退回规则 kb={} error={}", kbId, ex.getMessage());
            return Map.of();
        }
    }

    private String buildPrompt(List<ItemRef> batch, List<KbCategory> candidates) {
        return buildPrompt(batch, candidates, true);
    }

    private String buildPrompt(List<ItemRef> batch, List<KbCategory> candidates, boolean allowNewPath) {
        StringBuilder categories = new StringBuilder();
        for (KbCategory category : candidates) {
            categories.append("- id=").append(category.getId())
                    .append(" | ").append(category.pathKey())
                    .append(" | ").append(category.name());
            String description = category.description();
            if (description != null && !description.isBlank()) {
                categories.append(" | 说明：").append(escape(truncate(description, 40)));
            }
            categories.append('\n');
        }
        StringBuilder items = new StringBuilder();
        for (ItemRef item : batch) {
            items.append("{\"itemId\":").append(item.id())
                    .append(",\"type\":\"").append(item.type()).append("\",")
                    .append("\"title\":\"").append(escape(item.title())).append("\",")
                    .append("\"text\":\"").append(escape(truncate(item.body()))).append("\"}\n");
        }
        String rules = allowNewPath
                ? """
                规则：
                1. 能归到已有目录时，返回 categoryId，path 返回 null。
                2. 没有任何已有目录合适时，返回 path，用 / 分隔，最多 3 级，例如 java/java1、八股/redis。
                3. path 必须是稳定主题名，不能带题号、公司、年份、文档名；同一主题必须返回完全相同的 path。
                4. path 尽量保留常见大小写，例如 Java/集合。
                5. 无法判断时 categoryId 和 path 都返回 null。
                6. 「说明」写明了这个目录放什么、不放什么，以说明为准，不要只看名字像不像。
                7. 题目里出现的词只是线索，不是结论：例如 Memory 是 MySQL 的一个存储引擎名，
                   不代表这道题属于 JVM 的「内存结构」；判断依据是这道题在问哪个主题的知识。
                """
                : """
                规则：
                1. 只能从上面列出的目录里选一个，返回它的 id。
                2. 不允许新建目录：path 一律返回 null。这些目录就是该主题下的全部位置。
                3. 确实都不合适时，categoryId 和 path 都返回 null，交给人工处理。
                4. 「说明」写明了这个目录放什么、不放什么，以说明为准，不要只看名字像不像。
                5. 题目里出现的词只是线索，不是结论：例如 Memory 是 MySQL 的一个存储引擎名，
                   不代表这道题属于 JVM 的「内存结构」；判断依据是这道题在问哪个主题的知识。
                """;
        return """
                你是知识库归类器。优先复用已有目录；只有确实不合适时才创建一个稳定的新路径。
                目录（id | pathKey | 名称 | 说明）：
                %s
                待归类内容（JSONL）：
                %s
                %s
                只返回 JSON 数组，不要 Markdown：
                [{"itemId": 1, "categoryId": 2, "path": null, "confidence": 0.83},
                 {"itemId": 2, "categoryId": null, "path": "Java/集合", "confidence": 0.81}]
                confidence 取值 0~1，表示内容与目录的匹配程度。
                """.formatted(categories, items, rules);
    }

    private Map<Long, Proposal> parseProposals(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        String text = content.strip();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            JsonNode array = mapper.readTree(text.substring(start, end + 1));
            Map<Long, Proposal> proposals = new HashMap<>();
            for (JsonNode node : array) {
                long itemId = node.path("itemId").asLong(0);
                long categoryId = node.path("categoryId").asLong(0);
                String path = node.path("path").isTextual() ? node.path("path").asText().strip() : "";
                double confidence = node.path("confidence").asDouble(0);
                if (itemId > 0) {
                    proposals.put(itemId, new Proposal(
                            categoryId > 0 ? categoryId : null,
                            path.isBlank() ? null : path,
                            Math.min(1, Math.max(0, confidence))));
                }
            }
            return proposals;
        } catch (Exception ex) {
            log.warn("自动归类 LLM 返回无法解析：{}", ex.getMessage());
            return Map.of();
        }
    }

    /** 按 LLM 给的路径逐级复用/创建目录；最多 3 级，同名不区分大小写。 */
    private synchronized KbCategory ensureCategory(Long kbId, String rawPath) {
        String normalized = rawPath.replace('>', '/').replace('\\', '/');
        KbCategory parent = null;
        for (String raw : normalized.split("/")) {
            String name = raw.strip();
            if (name.isBlank()) {
                continue;
            }
            if (parent != null && parent.depth() >= KbCategory.MAX_DEPTH) {
                break;
            }
            long parentId = parent == null ? KbCategory.ROOT_PARENT_ID : parent.getId();
            KbCategory found = categories.findByKb(kbId).stream()
                    .filter(node -> node.parentId() == parentId && node.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                try {
                    KbCategoryView view = catalog.create(kbId, parent == null ? null : parent.getId(),
                            name, null, name);
                    found = categories.findById(view.id());
                } catch (BusinessException ex) {
                    // 并发上传时另一个线程可能刚好建了同名节点，重新查一次即可。
                    found = categories.findByKb(kbId).stream()
                            .filter(node -> node.parentId() == parentId && node.name().equalsIgnoreCase(name))
                            .findFirst()
                            .orElse(null);
                    if (found == null) {
                        log.warn("自动建目录失败 kb={} path={} error={}", kbId, rawPath, ex.getMessage());
                        return null;
                    }
                }
            }
            parent = found;
        }
        return parent;
    }

    private Match bestKeywordMatch(ItemRef item, List<KbCategory> candidates) {
        String haystack = (item.title() + " " + item.body()).toLowerCase(Locale.ROOT);
        Match best = null;
        for (KbCategory category : candidates) {
            String name = category.name().toLowerCase(Locale.ROOT).replace(" ", "");
            double score = 0;
            if (name.length() >= 2 && haystack.replace(" ", "").contains(name)) {
                score = 0.82;
            } else {
                String key = category.pathKey();
                String tail = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                String token = tail.replace('-', ' ').replace('_', ' ').trim();
                if (token.length() >= 3 && haystack.contains(token)) {
                    score = 0.74;
                } else {
                    // 节点名整串命中不了（"持久化（RDB/AOF）"这类带括号的长名）就退到关键片段：
                    // 命中 ≥2 个片段才算，命中越多分越高。纯本地兜底，模型不可用时才有意义。
                    score = fragmentScore(haystack, category.name());
                }
            }
            if (score > 0 && (best == null || score > best.score()
                    || (score == best.score() && category.depth() > best.depth()))) {
                best = new Match(category.getId(), score, category.depth());
            }
        }
        return best;
    }

    /** 太泛的片段不参与计分，否则"与/和/场景"这类词会把所有节点都点亮。 */
    private static final java.util.Set<String> FRAGMENT_STOP_WORDS = java.util.Set.of(
            "与", "和", "及", "的", "机制", "原理", "场景", "模型", "设计", "对比", "治理", "处理");

    /**
     * 按片段匹配节点名：把名字切成英文技术词与中文短词，命中数 ≥2 才给分（0.72 起，上限 0.78）。
     *
     * <p>典型用例：节点「持久化（RDB/AOF）」对手册里"Redis 持久化…RDB 是快照…AOF 追加日志"的切片——
     * 整串名字当然不会出现在正文里，但 RDB / AOF / 持久化三个片段都在，足够挂上去让人确认。
     * 中文按 2-gram 展开（"事务传播与失效场景" → 事务/传播/失效/场景 都能各自命中），
     * 否则中文连写永远匹配不到，规则兜底等于没有。</p>
     */
    static double fragmentScore(String haystack, String name) {
        if (haystack == null || name == null || name.isBlank()) {
            return 0;
        }
        String[] parts = name.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9+#.]+");
        int hits = 0;
        for (String part : parts) {
            if (part.length() < 2 || FRAGMENT_STOP_WORDS.contains(part)) {
                continue;
            }
            boolean han = part.codePoints().anyMatch(Character::isIdeographic);
            if (!han) {
                // 英文技术词（kafka / rdb / aof）整词命中才算
                if (part.length() <= 12 && haystack.contains(part)) {
                    hits++;
                }
                continue;
            }
            if (part.length() == 2) {
                if (!FRAGMENT_STOP_WORDS.contains(part) && haystack.contains(part)) {
                    hits++;
                }
                continue;
            }
            for (int i = 0; i + 2 <= part.length(); i++) {
                String gram = part.substring(i, i + 2);
                if (!FRAGMENT_STOP_WORDS.contains(gram) && haystack.contains(gram)) {
                    hits++;
                }
            }
        }
        if (hits < 2) {
            return 0;
        }
        return Math.min(0.78, 0.72 + 0.02 * (hits - 2));
    }

    private static String truncate(String text, int limit) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit);
    }

    private static String truncate(String text) {
        return truncate(text, TEXT_LIMIT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    record ItemRef(Long id, String title, String body, KbItemType type, Long documentId) {
    }

    private record Proposal(Long categoryId, String path, double confidence) {
    }

    private record Match(Long categoryId, double score, int depth) {
    }

    /**
     * 一次归类的结果报告。给**人**看，不是监控指标：人只问两句——这次挂上几条？没挂上的去哪了？
     *
     * <p>数字全部按"落库前后的差集"算，所以连点两次不会出现第二次还报"挂了 8 条"，
     * 也不会出现条目数（8）和报告数（12）对不上的情况。</p>
     *
     * @param total         本次参与归类的条目数
     * @param assigned      本次新挂上（或改挂）的条目数
     * @param alreadyPinned 本来就已人工确认、这次跳过的条目数
     * @param pending       挂上了但还没人确认的条目数（页面上叫「待确认」）
     * @param failed        没挂上、落进「未分类」的条目数
     * @param mode          LLM / RULE / NONE
     * @param cleared       本次先清掉的低置信挂载数（只出现在"按文档重跑"时，其它场景恒为 0）
     * @param summary       一句人话，页面直接显示（后端拼，避免前端各拼一套）
     */
    public record KbClassifyResult(int total, int assigned, int alreadyPinned, int pending, int failed,
                                   String mode, int cleared, String summary) {

        public KbClassifyResult(int total, int assigned, int alreadyPinned, int pending, int failed,
                                String mode, int cleared) {
            this(total, assigned, alreadyPinned, pending, failed, mode, cleared,
                    buildSummary(assigned, pending, alreadyPinned, failed));
        }

        private static String buildSummary(int assigned, int pending, int alreadyPinned, int failed) {
            StringBuilder sb = new StringBuilder("这次挂上 ").append(assigned).append(" 条");
            if (pending > 0) {
                sb.append(" · ").append(pending).append(" 条待确认");
            }
            if (alreadyPinned > 0) {
                sb.append(" · ").append(alreadyPinned).append(" 条已确认跳过");
            }
            sb.append(" · ").append(failed).append(" 条落在未分类");
            return sb.toString();
        }
    }
}
