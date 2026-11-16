package com.panel.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档级类型闸门：这份文档是「题库」还是「知识点讲义」。
 *
 * <p>为什么需要它：{@code KbKind} 是知识库级的，题库库里所有文档都会走
 * {@link KbQuestionParser}；而解析器最后一行兜底是"只要有正文就出一题"，
 * 于是一篇《HashMap 扩容与树化专项总结》、一份 README、一份面试口播稿，
 * 都会被切成"题"挂进目录——用户看到的就是"知识点被放进了面试题里"。</p>
 *
 * <p>判定顺序：先规则打分（确定性、可解释、零成本），只有落在
 * {@code [0.40, 0.55]} 这段模型才可能不同意的区间才问一次模型；模型不可用时
 * <b>保守判题库</b>——少抽一道题可以重跑，误删一批题才是事故。</p>
 *
 * <p>分数是"题库倾向"，可正可负。每一项都会写进 {@link Verdict#evidence()}，
 * 便于在日志里回答"为什么这篇没抽题"。</p>
 */
@Service
public class KbDocumentKindGate {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentKindGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 分数 ≥ 此值：题库。 */
    static final double BANK_THRESHOLD = 0.55;
    /** 分数 ≤ 此值：知识点。两者之间交模型。 */
    static final double NOTES_THRESHOLD = 0.40;

    private static final int LLM_TEXT_LIMIT = 1_500;
    private static final int LLM_STEM_LIMIT = 20;

    /** 文件名/标题里出现这些词，基本就是题库。 */
    private static final Pattern BANK_FILE = Pattern.compile(
            "(面经|面试题|真题|笔试题|八股|题库|interview|q&a|qa\\b)", Pattern.CASE_INSENSITIVE);

    /** 出现这些词的是笔记/讲义/说明，不是题目集。 */
    private static final Pattern NOTES_FILE = Pattern.compile(
            "(总结|专项|笔记|整理|清单|要点|准备|手册|指南|攻略|readme|说明|目录|复盘|速查)",
            Pattern.CASE_INSENSITIVE);

    /** 章级结构：一、二、三… 或 第N章。讲义几乎必有，题目集很少按"章"组织。 */
    private static final Pattern CHAPTER = Pattern.compile(
            "(?m)^\\s*(?:#{1,6}\\s*)?(?:[一二三四五六七八九十]+[、.．]|第[一二三四五六七八九十\\d]+[章节])");

    /** 题号行：1. / 2、/ 3)。 */
    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*(?:#{1,6}\\s*)?(\\d{1,3})\\s*[.、)）]\\s*\\S");

    /** "答案：/ 解析：/ 参考答案" 这类显式答题标记。 */
    private static final Pattern ANSWER_MARK = Pattern.compile(
            "(参考答案|答案\\s*[:：]|解析\\s*[:：]|^\\s*答\\s*[:：])", Pattern.MULTILINE);

    /** 面试语境词：面经实录里高频，技术总结里低频。 */
    private static final Pattern INTERVIEW_WORD = Pattern.compile(
            "面试|一面|二面|三面|四面|HR面|笔试|候选人|面试官|offer", Pattern.CASE_INSENSITIVE);

    /** 模板字段名：README 式的"简短回答/详细解析/常见追问"会被解析器当成题目。 */
    private static final List<String> TEMPLATE_FIELDS = List.of(
            "简短回答", "详细解析", "常见追问", "回答要点", "关联知识", "面试题目",
            "口播版", "一分钟版", "两分钟版", "技术深挖", "一句话结论", "使用策略", "预判追问");

    /** 小节名被当成题干的典型形状。 */
    private static final Pattern PSEUDO_STEM = Pattern.compile(
            ".*(口播版|分钟版|技术深挖|回答要点|一句话结论|使用策略|预判追问|必背|加固的方向|主动承认边界|"
                    + "面试题目|简短回答|详细解析|常见追问|关联知识|答案|解析|总结|示例|代码|目录|前言|说明|概述).*");

    private final ChatModelGateway chat;
    private final boolean enabled;
    private final boolean llmBoundary;

    public KbDocumentKindGate(ChatModelGateway chat,
                              @Value("${panel.knowledge.doc-gate.enabled:true}") boolean enabled,
                              @Value("${panel.knowledge.doc-gate.llm-boundary:true}") boolean llmBoundary) {
        this.chat = chat;
        this.enabled = enabled;
        this.llmBoundary = llmBoundary;
    }

    /** 测试/纯规则场景用：不调模型。 */
    static KbDocumentKindGate rulesOnly() {
        return new KbDocumentKindGate(null, true, false);
    }

    /**
     * @param fileName 原始文件名（含扩展名），只看名字就能挡掉一半
     * @param text     整篇正文（解析后的纯文本）
     * @param drafts   {@link KbQuestionParser} 试抽出来的题；用来看"抽出来的东西像不像题"
     */
    public Verdict judge(String fileName, String text, List<KbQuestionDraft> drafts) {
        List<KbQuestionDraft> questions = drafts == null ? List.of() : drafts;
        if (!enabled) {
            return new Verdict(true, 1.0, "OFF", List.of("闸门已关闭（panel.knowledge.doc-gate.enabled=false）"));
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String body = text == null ? "" : text;
        List<String> evidence = new ArrayList<>();
        double score = 0;

        if (BANK_FILE.matcher(name).find()) {
            score += 0.15;
            evidence.add("文件名/标题像题库 +0.15");
        }
        if (NOTES_FILE.matcher(name).find()) {
            score -= 0.30;
            evidence.add("文件名/标题像笔记·总结 −0.30");
        }

        double questionLike = ratio(questions, draft -> looksLikeQuestion(draft.stem()));
        if (questionLike >= 0.60) {
            score += 0.25;
            evidence.add(String.format("题面像问题 %.0f%% +0.25", questionLike * 100));
        } else if (questionLike >= 0.35) {
            score += 0.12;
            evidence.add(String.format("题面像问题 %.0f%% +0.12", questionLike * 100));
        } else if (!questions.isEmpty()) {
            evidence.add(String.format("题面像问题 %.0f%%（不加分）", questionLike * 100));
        }

        int run = longestNumberRun(body);
        if (run >= 3) {
            score += 0.25;
            evidence.add("题号连续到 " + run + " +0.25");
        }

        double avgAnswer = averageAnswerLength(questions);
        if (avgAnswer >= 80 && avgAnswer <= 800) {
            score += 0.10;
            evidence.add("答案长度适中 +0.10");
        } else if (avgAnswer >= 0 && avgAnswer < 40) {
            score -= 0.20;
            evidence.add("答案普遍为空或极短 −0.20");
        }

        if (ANSWER_MARK.matcher(body).find()) {
            score += 0.15;
            evidence.add("有「答案/解析」标记 +0.15");
        }

        int interviewWords = countMatches(INTERVIEW_WORD, body);
        if (interviewWords >= 3) {
            score += 0.15;
            evidence.add("面试语境词 " + interviewWords + " 处 +0.15");
        }

        int chapters = countMatches(CHAPTER, body);
        if (chapters >= 2) {
            score -= 0.15;
            evidence.add("章级结构 " + chapters + " 处 −0.15");
        }

        int templateHits = countTemplateFields(body);
        // 口径是"整篇铺满模板字段"（README 那种），不是"出现过两次"：面经里本来就有
        // 「回答要点」「常见追问」小节，按 2 次扣分会把真题集一起判成讲义（实测 5 份面经被误杀）。
        if (templateHits >= 4) {
            score -= 0.25;
            evidence.add("模板字段 " + templateHits + " 个 −0.25");
        }

        double pseudoRatio = ratio(questions, draft -> PSEUDO_STEM.matcher(safe(draft.stem())).matches());
        if (pseudoRatio >= 0.30) {
            score -= 0.30;
            evidence.add(String.format("伪题（小节目录名）占 %.0f%% −0.30", pseudoRatio * 100));
        }

        score = Math.round(score * 100) / 100.0;
        if (score >= BANK_THRESHOLD) {
            return new Verdict(true, score, "RULE", evidence);
        }
        if (score < NOTES_THRESHOLD || !llmBoundary) {
            return new Verdict(false, score, "RULE", evidence);
        }

        Boolean byModel = askModel(fileName, body, questions);
        if (byModel == null) {
            List<String> fallback = new ArrayList<>(evidence);
            fallback.add("分数在边界区间且模型不可用，保守按题库处理");
            return new Verdict(true, score, "FALLBACK", fallback);
        }
        List<String> merged = new ArrayList<>(evidence);
        merged.add("边界区间交模型判定：" + (byModel ? "题库" : "知识点"));
        return new Verdict(byModel, score, "LLM", merged);
    }

    /**
     * 边界区间问一次模型。
     *
     * @return null 表示模型不可用或返回不可解析——调用方按题库保守处理
     */
    private Boolean askModel(String fileName, String text, List<KbQuestionDraft> drafts) {
        if (chat == null) {
            return null;
        }
        StringBuilder stems = new StringBuilder();
        for (KbQuestionDraft draft : drafts.stream().limit(LLM_STEM_LIMIT).toList()) {
            stems.append("- ").append(flatten(draft.stem())).append('\n');
        }
        String flat = flatten(text);
        String prompt = """
                判断这份文档是「面试题库」还是「知识点讲义/笔记」。
                题库：一题一答、题目彼此独立、可以拿去抽题刷题。
                讲义：围绕一个主题系统讲解，小节之间是递进关系，抽成题会丢失上下文。

                文件名：%s
                试抽出的题干：
                %s
                正文节选：
                %s

                只返回 JSON：{"isQuestionBank": true, "reason": "一句话依据"}
                """.formatted(fileName == null ? "" : fileName,
                stems.isEmpty() ? "（无）" : stems.toString(),
                flat.length() <= LLM_TEXT_LIMIT ? flat : flat.substring(0, LLM_TEXT_LIMIT));
        try {
            ChatModelCallResult result = chat.completeStructuredJson(prompt, List.of());
            String content = result == null ? null : result.content();
            if (content == null || content.isBlank()) {
                return null;
            }
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = MAPPER.readTree(content.substring(start, end + 1));
            if (!node.has("isQuestionBank")) {
                return null;
            }
            boolean bank = node.path("isQuestionBank").asBoolean(true);
            log.info("文档类型闸门交模型判定：file={} -> {} ({})", fileName, bank,
                    node.path("reason").asText(""));
            return bank;
        } catch (Exception ex) {
            log.warn("文档类型闸门模型判定失败，按题库保守处理 file={} error={}", fileName, ex.getMessage());
            return null;
        }
    }

    /** 题号最长连续段：1,2,3,4 → 4。讲义里每章从 1 重来，跑不长。 */
    static int longestNumberRun(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int best = 0;
        int current = 0;
        int previous = -1;
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            Matcher matcher = NUMBERED_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int number = Integer.parseInt(matcher.group(1));
            if (previous < 0) {
                current = number <= 2 ? 1 : 0;
            } else if (number == previous + 1) {
                current++;
            } else if (number > previous) {
                current = 1;
            } else {
                current = number <= 2 ? 1 : 0;
            }
            previous = number;
            best = Math.max(best, current);
        }
        return best;
    }

    private static boolean looksLikeQuestion(String stem) {
        String value = safe(stem).strip();
        if (value.isEmpty()) {
            return false;
        }
        if (value.endsWith("?") || value.endsWith("？")) {
            return true;
        }
        return value.matches("^(?:如何|为什么|为何|怎么|怎样|什么|哪些|是否|谈谈|说说|聊聊|介绍|讲解|描述|解释|对比|比较).*");
    }

    private static double ratio(List<KbQuestionDraft> drafts, java.util.function.Predicate<KbQuestionDraft> test) {
        if (drafts.isEmpty()) {
            return 0;
        }
        long hit = drafts.stream().filter(test).count();
        return (double) hit / drafts.size();
    }

    private static double averageAnswerLength(List<KbQuestionDraft> drafts) {
        if (drafts.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (KbQuestionDraft draft : drafts) {
            sum += draft.answer() == null ? 0 : draft.answer().strip().length();
        }
        return sum / drafts.size();
    }

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countTemplateFields(String text) {
        int hits = 0;
        for (String field : TEMPLATE_FIELDS) {
            if (text.contains(field)) {
                hits++;
            }
        }
        return hits;
    }

    private static String flatten(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 闸门结论。
     *
     * @param questionBank true = 抽题入库；false = 当知识点，按切片归类
     * @param score        题库倾向分（可负）
     * @param source       RULE / LLM / FALLBACK / OFF
     * @param evidence     逐项加减分，日志里能直接回答"为什么"
     */
    public record Verdict(boolean questionBank, double score, String source, List<String> evidence) {

        public String summary() {
            return String.join("；", evidence);
        }
    }
}
