package com.panel.knowledge.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbCategoryRelation;
import com.panel.knowledge.domain.model.KbItemCategory;
import com.panel.knowledge.domain.model.KbItemType;
import com.panel.knowledge.domain.model.KbQuestion;
import com.panel.knowledge.domain.model.KbQuizAttempt;
import com.panel.knowledge.domain.model.KbQuizSession;
import com.panel.knowledge.domain.model.KnowledgeBase;
import com.panel.knowledge.infrastructure.persistence.KbCategoryRelationStore;
import com.panel.knowledge.infrastructure.persistence.KbCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbItemCategoryStore;
import com.panel.knowledge.infrastructure.persistence.KbQuestionStore;
import com.panel.knowledge.infrastructure.persistence.KbQuizStore;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 刷题闭环：分层加权抽题 → 只看题面 → 展开答案 → 自评 → 错题定位到知识点。
 *
 * <p>刻意不调用检索/向量：抽题是抽样问题、知识点是目录问题，两者都不需要相似度。
 * 唯一需要“判对错”的地方是用户自己，所以答案要等到 reveal 才下发。</p>
 */
@Service
public class KbQuizService {

    private static final Logger log = LoggerFactory.getLogger(KbQuizService.class);

    public static final int DEFAULT_SIZE = 5;
    public static final int MAX_SIZE = 20;
    /** 同一知识点一次最多抽几题：避免 5 题全砸在一个节点上。 */
    private static final int MAX_PER_NODE = 2;
    private static final int RECENT_CORRECT_DAYS = 3;

    private static final double W_NEVER = 1.2;
    private static final double W_WRONG = 3.0;
    private static final double W_RECENT_CORRECT = 0.25;
    private static final double W_OLD_CORRECT = 0.8;

    /** 桶内权重：能力值 → 「下次答错概率」的指数映射，弱项自然被多抽。 */
    private static final double THETA_FLOOR = 0.3;
    private static final double THETA_SPAN = 3.0;
    private static final double THETA_POWER = 1.5;
    /** 近期错过（3 天内）的额外加成：能力值已经吸收了错误，这里再强调一次。 */
    private static final double RECENT_WRONG_BOOST = 1.5;

    private final KbQuestionStore questions;
    /** 抽题权重是否走能力值（关闭后退回旧的固定启发式，方便 A/B 对比）。 */
    @org.springframework.beans.factory.annotation.Value("${panel.kb.quiz.skill-weight:true}")
    private boolean skillWeight = true;
    private final KbQuizStore quiz;
    private final KbItemCategoryStore items;
    private final KbCategoryStore categories;
    private final KbCategoryRelationStore relations;
    private final KnowledgeBaseRepository bases;
    private final KbAccessGuard guard;
    private final KbSkillService skillService;
    private final ObjectMapper mapper;

    public KbQuizService(KbQuestionStore questions,
                         KbQuizStore quiz,
                         KbItemCategoryStore items,
                         KbCategoryStore categories,
                         KbCategoryRelationStore relations,
                         KnowledgeBaseRepository bases,
                         KbAccessGuard guard,
                         KbSkillService skillService,
                         ObjectMapper mapper) {
        this.questions = questions;
        this.quiz = quiz;
        this.items = items;
        this.categories = categories;
        this.relations = relations;
        this.bases = bases;
        this.guard = guard;
        this.skillService = skillService;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ 抽题

    @Transactional
    public KbQuizViews.Session start(Long kbId, Long userId, Integer size) {
        requireBase(kbId);
        int target = clamp(size == null ? DEFAULT_SIZE : size, 1, MAX_SIZE);
        List<KbQuestion> pool = questions.findAllByKb(kbId);
        if (pool.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "题库里还没有题目：先上传题库文档，等入库完成（READY）后再抽题");
        }
        Map<Long, Long> categoryOf = primaryCategoryOf(kbId);
        Map<Long, KbQuizAttempt> latest = latestAttempts(userId, kbId);
        // 能力值驱动的权重：开关关掉就传空表，自动退回旧的固定启发式（compare 用）。
        Map<Long, Double> thetaOf = skillWeight ? skillService.effectiveThetas(kbId, userId) : Map.of();
        Map<Long, KbQuestion> byId = new HashMap<>();
        List<Long> candidates = new ArrayList<>(pool.size());
        for (KbQuestion question : pool) {
            byId.put(question.getId(), question);
            candidates.add(question.getId());
        }
        List<Long> picked = sample(candidates, categoryOf, latest, thetaOf,
                Math.min(target, candidates.size()));

        KbQuizSession session = quiz.saveSession(KbQuizSession.start(userId, kbId, picked.size()));
        List<KbQuizAttempt> attempts = new ArrayList<>(picked.size());
        for (int i = 0; i < picked.size(); i++) {
            Long questionId = picked.get(i);
            attempts.add(KbQuizAttempt.create(session.getId(), userId, kbId, questionId, i,
                    categoryOf.get(questionId)));
        }
        quiz.saveAttempts(attempts);
        log.info("刷题开新会话 kbId={} userId={} 题数={}", kbId, userId, attempts.size());
        return toSessionView(session, attempts, byId);
    }

    @Transactional(readOnly = true)
    public KbQuizViews.Session session(Long sessionId, Long userId) {
        KbQuizSession session = requireSession(sessionId, userId);
        List<KbQuizAttempt> attempts = quiz.findAttempts(sessionId);
        return toSessionView(session, attempts, questionsOf(attempts));
    }

    /** 从看到题面到点开答案多久；顺手把知识点一起带出来，方便错题页直接跳。 */
    @Transactional
    public KbQuizViews.Reveal reveal(Long attemptId, Long userId) {
        KbQuizAttempt attempt = requireAttempt(attemptId, userId);
        KbQuestion question = questions.findById(attempt.questionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "题目已被删除：" + attempt.questionId());
        }
        attempt.reveal();
        quiz.saveAttempt(attempt);
        Map<Long, KbCategory> tree = categoryTree(attempt.kbId());
        return new KbQuizViews.Reveal(attempt.getId(), question.getId(), question.stem(), question.answer(),
                attempt.thinkingSeconds(), point(attempt.kbId(), attempt.categoryId(), tree),
                related(attempt.kbId(), attempt.categoryId(), tree));
    }

    @Transactional
    public KbQuizViews.Grade grade(Long attemptId, Long userId, String rawResult) {
        KbQuizAttempt.Result result;
        try {
            result = KbQuizAttempt.Result.parse(rawResult);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        KbQuizAttempt attempt = requireAttempt(attemptId, userId);
        attempt.grade(result);
        quiz.saveAttempt(attempt);

        // 能力值：只在自评那一刻更新，同一题重复自评按多次观测算（学习率随次数递减，影响可控）。
        KbQuestion graded = questions.findById(attempt.questionId());
        skillService.record(userId, attempt.kbId(), attempt.categoryId(), result.name(),
                graded == null ? null : graded.difficulty(), false);

        KbQuizSession session = requireSession(attempt.sessionId(), userId);
        List<KbQuizAttempt> attempts = quiz.findAttempts(session.getId());
        int answered = 0;
        int correct = 0;
        int wrong = 0;
        for (KbQuizAttempt item : attempts) {
            if (!item.answered()) {
                continue;
            }
            answered++;
            if (KbQuizAttempt.Result.CORRECT.name().equals(item.result())) {
                correct++;
            } else if (!KbQuizAttempt.Result.SKIPPED.name().equals(item.result())) {
                wrong++;
            }
        }
        session.applyCounts(correct, wrong);
        boolean finished = answered >= attempts.size() && !attempts.isEmpty();
        if (finished && session.running()) {
            session.finish();
        }
        if (finished) {
            quiz.saveSession(session);
        }

        KbQuizViews.Summary summary = finished ? summaryOf(attempts, attempts.size()) : null;
        Map<Long, KbCategory> tree = categoryTree(attempt.kbId());
        boolean ok = result.countsAsCorrect();
        return new KbQuizViews.Grade(attempt.getId(), result.name(), ok, answered, attempts.size(),
                finished, summary,
                ok ? null : point(attempt.kbId(), attempt.categoryId(), tree),
                ok ? List.of() : related(attempt.kbId(), attempt.categoryId(), tree));
    }

    // ------------------------------------------------------------------ 统计

    @Transactional(readOnly = true)
    public KbQuizViews.Stats stats(Long kbId, Long userId) {
        requireBase(kbId);
        long total = questions.countByKb(kbId);
        long assigned = Math.min(total, items.findAssignedItemIds(kbId, KbItemType.QUESTION).size());
        long attempted = quiz.countDistinctQuestions(userId, kbId);
        long correct = quiz.countByResult(userId, kbId, KbQuizAttempt.Result.CORRECT);
        long wrong = quiz.countByResult(userId, kbId, KbQuizAttempt.Result.WRONG);
        long unsure = quiz.countByResult(userId, kbId, KbQuizAttempt.Result.UNSURE);
        long answered = correct + wrong + unsure;
        // 分母用「已作答」而不是「对+错」：选了「不确定」等于没答对，不该把正确率抬上去。
        double accuracy = answered == 0 ? 0d : round((double) correct / answered);
        long wrongDistinct = quiz.findWrongQuestionIds(userId, kbId, 1000).size();

        Map<Long, KbCategory> tree = categoryTree(kbId);
        List<KbQuizViews.WeakPoint> weak = new ArrayList<>();
        for (Object[] row : quiz.wrongByCategory(userId, kbId)) {
            Long categoryId = (Long) row[0];
            long count = ((Number) row[1]).longValue();
            KbCategory node = categoryId == null ? null : tree.get(categoryId);
            weak.add(new KbQuizViews.WeakPoint(categoryId,
                    node == null ? "未分类" : node.name(),
                    node == null ? null : pathOf(node, tree), count));
            if (weak.size() >= 5) {
                break;
            }
        }
        List<KbQuizViews.Session> recent = new ArrayList<>();
        for (var session : quiz.recentSessions(userId, kbId, 5)) {
            List<KbQuizAttempt> attempts = quiz.findAttempts(session.getId());
            Map<Long, KbQuestion> alive = questionsOf(attempts);
            // 重传文档会把旧题删掉，旧会话只剩"（题目已删除）"的空壳。
            // 这种会话对用户没有任何信息量，列出来只会让人以为数据坏了。
            if (alive.isEmpty()) {
                continue;
            }
            recent.add(toSessionView(session, attempts, alive));
        }
        return new KbQuizViews.Stats(kbId, total, assigned, attempted, answered, correct, wrong, unsure,
                accuracy, wrongDistinct, weak, recent);
    }

    /**
     * 能力值总览：叶子按弱到强排，父节点按题量加权聚合。
     *
     * <p>这里**不要求**是题库：面试信号会记在"岗位与公司""技术知识"这类库的知识点上，
     * 能力值本来就是跨库的概念，只有抽题才必须落在题库上。</p>
     */
    @Transactional(readOnly = true)
    public KbQuizViews.Skills skills(Long kbId, Long userId) {
        guard.ensureReadable(bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId)));
        return skillService.skills(kbId, userId);
    }

    /** 错题本：按错得次数排序，带上知识点路径，点一下就回目录。 */
    @Transactional(readOnly = true)
    public List<KbQuizViews.WrongItem> wrongBook(Long kbId, Long userId, int limit) {
        requireBase(kbId);
        List<Long> ids = quiz.findWrongQuestionIds(userId, kbId, clamp(limit, 1, 200));
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, KbQuestion> byId = new HashMap<>();
        for (KbQuestion question : questions.findByIds(ids)) {
            byId.put(question.getId(), question);
        }
        Map<Long, Long> categoryOf = primaryCategoryOf(kbId);
        Map<Long, KbCategory> tree = categoryTree(kbId);
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : quiz.wrongCountPerQuestion(userId, kbId)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        List<KbQuizViews.WrongItem> result = new ArrayList<>(ids.size());
        Set<Long> used = new HashSet<>();
        for (Long id : ids) {
            KbQuestion question = byId.get(id);
            if (question == null || !used.add(id)) {
                continue;
            }
            Long categoryId = categoryOf.get(id);
            KbCategory node = categoryId == null ? null : tree.get(categoryId);
            result.add(new KbQuizViews.WrongItem(id, question.stem(), categoryId,
                    node == null ? null : pathOf(node, tree), counts.getOrDefault(id, 1L)));
        }
        return result;
    }

    // ------------------------------------------------------------- 抽题算法

    /**
     * 分层 + 加权：
     * <ol>
     *   <li>先按知识点分桶，**按桶权重抽桶**（桶权重 = 桶内最弱那道题的权重），抽中一次只给 1 个名额，
     *       保证 5 题不砸在同一个节点，同时让弱节点被抽到的概率更高；</li>
     *   <li>单题权重：有能力值就按「下次答错概率^1.5」给（越弱越高），再乘近期因子
     *       （近 3 天错过 ×1.5、近 3 天做对 ×0.25、更早做对 ×0.8）；能力值表为空时退回旧的固定启发式
     *       （错 ×3.0、没做 ×1.2）；</li>
     *   <li>桶数少于题数时，第二轮继续按权重给有余量的桶补名额（每桶上限 {@code MAX_PER_NODE}）；</li>
     *   <li>还不够就从剩余池里兜底，直到凑够或题库见底。</li>
     * </ol>
     *
     * <p>注意：桶的优先级必须走加权，不能"先打乱再轮流取"——那样能力值只影响取题顺序，
     * 影响不了"哪些知识点出现在这一组里"，弱项加权就形同虚设。</p>
     */
    static List<Long> sample(List<Long> pool, Map<Long, Long> categoryOf,
                             Map<Long, KbQuizAttempt> latest, Map<Long, Double> thetaOf, int target) {
        if (pool.size() <= target) {
            List<Long> all = new ArrayList<>(pool);
            Collections.shuffle(all);
            return all;
        }
        Random random = new Random();
        Map<Long, List<Long>> buckets = new LinkedHashMap<>();
        for (Long questionId : pool) {
            Long key = categoryOf.getOrDefault(questionId, 0L);
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(questionId);
        }
        List<List<Long>> groups = new ArrayList<>(buckets.values());
        for (List<Long> group : groups) {
            Collections.shuffle(group, random);
        }

        List<Long> picked = new ArrayList<>(target);
        Set<Long> taken = new HashSet<>();

        // 第一轮：按桶权重抽桶，每个桶只给 1 题。
        List<List<Long>> fresh = new ArrayList<>(groups);
        while (picked.size() < target && !fresh.isEmpty()) {
            int index = pickGroup(fresh, latest, categoryOf, thetaOf, taken, random);
            if (index < 0) {
                break;
            }
            List<Long> group = fresh.get(index);
            Long next = pickWeighted(group, latest, categoryOf, thetaOf, taken, random);
            fresh.remove(index);
            if (next == null) {
                continue;
            }
            picked.add(next);
            taken.add(next);
        }

        // 第二轮：桶不够用，继续给有余量的桶补名额（每桶最多 MAX_PER_NODE）。
        while (picked.size() < target) {
            List<List<Long>> roomy = new ArrayList<>();
            for (List<Long> group : groups) {
                int fromGroup = 0;
                for (Long questionId : group) {
                    if (taken.contains(questionId)) {
                        fromGroup++;
                    }
                }
                if (fromGroup < MAX_PER_NODE && fromGroup < group.size()) {
                    roomy.add(group);
                }
            }
            if (roomy.isEmpty()) {
                break;
            }
            int index = pickGroup(roomy, latest, categoryOf, thetaOf, taken, random);
            if (index < 0) {
                break;
            }
            Long next = pickWeighted(roomy.get(index), latest, categoryOf, thetaOf, taken, random);
            if (next == null) {
                break;
            }
            picked.add(next);
            taken.add(next);
        }

        if (picked.size() < target) {
            List<Long> rest = new ArrayList<>();
            for (Long questionId : pool) {
                if (!taken.contains(questionId)) {
                    rest.add(questionId);
                }
            }
            Collections.shuffle(rest, random);
            for (Long questionId : rest) {
                if (picked.size() >= target) {
                    break;
                }
                picked.add(questionId);
            }
        }
        Collections.shuffle(picked, random);
        return picked;
    }

    /** 按桶权重轮盘赌选一个桶；桶权重 = 桶内未抽走的题里最弱的那道。 */
    private static int pickGroup(List<List<Long>> groups, Map<Long, KbQuizAttempt> latest,
                                 Map<Long, Long> categoryOf, Map<Long, Double> thetaOf,
                                 Set<Long> taken, Random random) {
        double total = 0;
        double[] weights = new double[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            double best = 0;
            for (Long questionId : groups.get(i)) {
                if (taken.contains(questionId)) {
                    continue;
                }
                best = Math.max(best, weightOf(questionId, latest, categoryOf, thetaOf));
            }
            weights[i] = best;
            total += best;
        }
        if (total <= 0) {
            return groups.isEmpty() ? -1 : random.nextInt(groups.size());
        }
        double point = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            point -= weights[i];
            if (point <= 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private static Long pickWeighted(List<Long> group, Map<Long, KbQuizAttempt> latest,
                                     Map<Long, Long> categoryOf, Map<Long, Double> thetaOf,
                                     Set<Long> taken, Random random) {
        List<Long> candidates = new ArrayList<>(group.size());
        double total = 0;
        for (Long questionId : group) {
            if (taken.contains(questionId)) {
                continue;
            }
            candidates.add(questionId);
            total += weightOf(questionId, latest, categoryOf, thetaOf);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (total <= 0) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        double point = random.nextDouble() * total;
        for (Long questionId : candidates) {
            point -= weightOf(questionId, latest, categoryOf, thetaOf);
            if (point <= 0) {
                return questionId;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * 单题权重。
     *
     * <p>能力值可用时：{@code base = 0.3 + 3.0 × wrongRate^1.5}，即"越可能答错越优先"；
     * 该知识点还没有能力值（没做过）时用 {@code W_NEVER} 兜底。最后统一乘近期因子，
     * 避免刚做对过的题反复出现。</p>
     */
    static double weightOf(Long questionId, Map<Long, KbQuizAttempt> latest,
                           Map<Long, Long> categoryOf, Map<Long, Double> thetaOf) {
        KbQuizAttempt attempt = latest.get(questionId);
        Double theta = thetaOf.isEmpty() ? null : thetaOf.get(categoryOf.get(questionId));
        double base;
        if (theta == null) {
            base = W_NEVER;
            if (attempt != null && KbQuizAttempt.Result.WRONG.name().equals(attempt.result())) {
                base = W_WRONG; // 没有能力值但有错题记录：仍然按"错过"加权
            }
        } else {
            double wrongRate = KbSkillModel.wrongRate(theta, 0d);
            base = THETA_FLOOR + THETA_SPAN * Math.pow(wrongRate, THETA_POWER);
        }
        return base * recencyFactor(attempt);
    }

    /** 近期因子：刚错过还要提、刚做对要压。 */
    private static double recencyFactor(KbQuizAttempt attempt) {
        if (attempt == null || attempt.result() == null) {
            return 1d;
        }
        if (KbQuizAttempt.Result.WRONG.name().equals(attempt.result())
                || KbQuizAttempt.Result.UNSURE.name().equals(attempt.result())) {
            return RECENT_WRONG_BOOST;
        }
        if (KbQuizAttempt.Result.SKIPPED.name().equals(attempt.result())) {
            return 1d;
        }
        Instant at = attempt.answeredAt() == null ? attempt.createdAt() : attempt.answeredAt();
        long days = Duration.between(at, Instant.now()).toDays();
        return days <= RECENT_CORRECT_DAYS ? W_RECENT_CORRECT : W_OLD_CORRECT;
    }

    // --------------------------------------------------------------- 视图组装

    private KbQuizViews.Session toSessionView(KbQuizSession session, List<KbQuizAttempt> attempts,
                                              Map<Long, KbQuestion> byId) {
        List<KbQuizViews.Item> items = new ArrayList<>(attempts.size());
        int answered = 0;
        int correct = 0;
        int wrong = 0;
        for (KbQuizAttempt attempt : attempts) {
            KbQuestion question = byId.get(attempt.questionId());
            if (attempt.answered()) {
                answered++;
                if (KbQuizAttempt.Result.CORRECT.name().equals(attempt.result())) {
                    correct++;
                } else if (!KbQuizAttempt.Result.SKIPPED.name().equals(attempt.result())) {
                    wrong++;
                }
            }
            items.add(new KbQuizViews.Item(attempt.getId(), attempt.ordinal(), attempt.questionId(),
                    question == null ? "（题目已删除）" : question.stem(),
                    question == null ? null : question.difficulty(),
                    question == null ? List.of() : tags(question.tagsJson()),
                    attempt.answered(), attempt.result()));
        }
        return new KbQuizViews.Session(session.getId(), session.kbId(), session.status(),
                session.questionCount(), answered, correct, wrong, session.startedAt(), session.finishedAt(),
                items);
    }

    private KbQuizViews.Summary summaryOf(List<KbQuizAttempt> attempts, int total) {
        Map<Long, KbQuestion> byId = questionsOf(attempts);
        Map<Long, KbCategory> tree = attempts.isEmpty() ? Map.of()
                : categoryTree(attempts.get(0).kbId());
        int correct = 0;
        int wrong = 0;
        List<KbQuizViews.WrongItem> wrongItems = new ArrayList<>();
        Map<Long, Integer> perQuestion = new HashMap<>();
        for (KbQuizAttempt attempt : attempts) {
            if (!attempt.answered()) {
                continue;
            }
            if (KbQuizAttempt.Result.CORRECT.name().equals(attempt.result())) {
                correct++;
                continue;
            }
            if (KbQuizAttempt.Result.SKIPPED.name().equals(attempt.result())) {
                continue;
            }
            wrong++;
            perQuestion.merge(attempt.questionId(), 1, Integer::sum);
            KbQuestion question = byId.get(attempt.questionId());
            KbCategory node = attempt.categoryId() == null ? null : tree.get(attempt.categoryId());
            wrongItems.add(new KbQuizViews.WrongItem(attempt.questionId(),
                    question == null ? "（题目已删除）" : question.stem(),
                    attempt.categoryId(), node == null ? null : pathOf(node, tree),
                    perQuestion.get(attempt.questionId())));
        }
        double accuracy = correct + wrong == 0 ? 0d : round((double) correct / (correct + wrong));
        return new KbQuizViews.Summary(correct, wrong, accuracy, wrongItems);
    }

    private Map<Long, KbQuestion> questionsOf(List<KbQuizAttempt> attempts) {
        List<Long> ids = attempts.stream().map(KbQuizAttempt::questionId).distinct().toList();
        Map<Long, KbQuestion> byId = new HashMap<>();
        for (KbQuestion question : questions.findByIds(ids)) {
            byId.put(question.getId(), question);
        }
        return byId;
    }

    private Map<Long, Long> primaryCategoryOf(Long kbId) {
        Map<Long, Long> result = new HashMap<>();
        for (KbItemCategory link : items.findAllByKb(kbId)) {
            if (link.itemType() != KbItemType.QUESTION) {
                continue;
            }
            Long current = result.get(link.itemId());
            if (current == null || link.primary()) {
                result.put(link.itemId(), link.categoryId());
            }
        }
        return result;
    }

    private Map<Long, KbQuizAttempt> latestAttempts(Long userId, Long kbId) {
        Map<Long, KbQuizAttempt> result = new HashMap<>();
        for (KbQuizAttempt attempt : quiz.findLatestPerQuestion(userId, kbId)) {
            result.put(attempt.questionId(), attempt);
        }
        return result;
    }

    private Map<Long, KbCategory> categoryTree(Long kbId) {
        Map<Long, KbCategory> tree = new HashMap<>();
        for (KbCategory category : categories.findByKb(kbId)) {
            tree.put(category.getId(), category);
        }
        return tree;
    }

    private KbQuizViews.Point point(Long kbId, Long categoryId, Map<Long, KbCategory> tree) {
        if (categoryId == null) {
            return null;
        }
        KbCategory node = tree.get(categoryId);
        if (node == null) {
            return null;
        }
        return new KbQuizViews.Point(node.getId(), node.name(), pathOf(node, tree),
                "/kb/" + kbId + "/catalog?node=" + node.getId(), node.description());
    }

    private List<KbQuizViews.Related> related(Long kbId, Long categoryId, Map<Long, KbCategory> tree) {
        if (categoryId == null) {
            return List.of();
        }
        List<KbQuizViews.Related> result = new ArrayList<>();
        for (KbCategoryRelation relation : relations.findByCategory(kbId, categoryId)) {
            boolean outgoing = categoryId.equals(relation.fromCategoryId());
            Long otherId = outgoing ? relation.toCategoryId() : relation.fromCategoryId();
            KbCategory other = tree.get(otherId);
            if (other == null) {
                continue;
            }
            result.add(new KbQuizViews.Related(other.getId(), other.name(), relation.relation(),
                    relation.weight() == null ? 0.5d : relation.weight(), outgoing ? "OUT" : "IN"));
        }
        return result;
    }

    private String pathOf(KbCategory node, Map<Long, KbCategory> tree) {
        List<String> names = new ArrayList<>();
        Set<Long> guard = new HashSet<>();
        KbCategory cursor = node;
        while (cursor != null && guard.add(cursor.getId())) {
            names.add(0, cursor.name());
            Long parentId = cursor.parentId();
            cursor = parentId == null || parentId == KbCategory.ROOT_PARENT_ID ? null : tree.get(parentId);
        }
        return String.join(" / ", names);
    }

    private List<String> tags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(tagsJson, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ 校验

    private void requireBase(Long kbId) {
        KnowledgeBase base = bases.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在：" + kbId));
        guard.ensureReadable(base);
        if (base.kind() != com.panel.knowledge.domain.model.KbKind.QUESTION_BANK) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有题库（QUESTION_BANK）可以抽题：「" + base.name() + "」不是题库");
        }
    }

    private KbQuizSession requireSession(Long sessionId, Long userId) {
        KbQuizSession session = quiz.findSession(sessionId);
        if (session == null || !session.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "刷题会话不存在：" + sessionId);
        }
        return session;
    }

    private KbQuizAttempt requireAttempt(Long attemptId, Long userId) {
        KbQuizAttempt attempt = quiz.findAttempt(attemptId);
        if (attempt == null || !attempt.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "作答记录不存在：" + attemptId);
        }
        return attempt;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
