package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题库 md → 结构化题目。
 *
 * <p>输入是已经切好的 {@link KbChunk}（标题 + 正文）。解析顺序：</p>
 * <ol>
 *   <li>正文里出现 {@code 1.} / {@code Q:} / {@code 问：} 等题号标记，且标记行像问题 → 按标记拆多题；</li>
 *   <li>切片标题本身像问题（含问号/疑问词，且不是“答案/解析”这种通用标题）→ 一问一答；</li>
 *   <li>兜底：取正文第一句当题干，整段当答案。</li>
 * </ol>
 * <p>格式兼容优先于精确：拆不出来时宁可“一片一题”，也不能把题丢掉。</p>
 */
@Component
public class KbQuestionParser {

    private static final int MAX_QUESTIONS_PER_DOCUMENT = 500;
    private static final int MAX_STEM = 300;
    private static final int MAX_ANSWER = 12_000;

    private static final Pattern NUMBERED = Pattern.compile("^\\s*(?<num>\\d{1,3})\\s*[.、)）]\\s*(?<text>.+)$");
    private static final Pattern HEADING = Pattern.compile("^\\s*(?<hashes>#{1,6})\\s+(?<text>.+)$");
    private static final Pattern PREFIXED = Pattern.compile("^\\s*(?:Q\\d*|问|题目|问题)\\s*[:：]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern TAG = Pattern.compile("#([\\p{IsHan}A-Za-z0-9_+-]{2,20})");
    private static final Pattern CONTAINER_HEADING = Pattern.compile(
            ".*(真题|题库|题集|合集|汇总|精选|大纲|面试题大全).*");
    private static final Set<String> GENERIC_HEADINGS = Set.of(
            "答案", "解析", "参考答案", "题目", "面试题", "问题", "目录", "前言", "概述", "说明",
            "示例", "代码", "总结", "背景", "扩展", "补充");
    private static final List<String> COMPANIES = List.of(
            "字节", "腾讯", "阿里", "百度", "美团", "京东", "拼多多", "网易", "快手", "滴滴",
            "华为", "小米", "小红书", "蚂蚁", "携程", "得物", "B站", "bilibili", "shopee", "shein");
    private static final List<String> ROLES = List.of(
            "后端", "前端", "算法", "数据", "测试", "运维", "客户端", "服务端", "全栈", "安全");

    public List<KbQuestionDraft> parse(List<KbChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<KbQuestionDraft> drafts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (KbChunk chunk : chunks) {
            if (drafts.size() >= MAX_QUESTIONS_PER_DOCUMENT) {
                break;
            }
            for (KbQuestionDraft draft : parseChunk(chunk)) {
                String key = com.panel.knowledge.domain.model.KbQuestion.fingerprint(draft.stem());
                if (seen.add(key)) {
                    drafts.add(draft);
                }
            }
        }
        return drafts;
    }

    private List<KbQuestionDraft> parseChunk(KbChunk chunk) {
        String body = stripHeading(chunk.text(), chunk.heading());
        String heading = chunk.heading() == null ? "" : chunk.heading().strip();
        List<KbQuestionDraft> drafts = new ArrayList<>();
        for (RawQuestion raw : rawQuestions(heading, body)) {
            KbQuestionDraft draft = toDraft(raw, heading, body, chunk.getId());
            if (draft != null) {
                drafts.add(draft);
            }
        }
        return drafts;
    }

    /**
     * 以整篇正文拆题，再把每道题挂回覆盖它的切片。
     *
     * <p>切片是检索单位，题目是业务单位，两者粒度不同：一份 8 题的文档被切成 4 片后，
     * 逐片解析会在"题目跨切片"的地方丢题，还会把答案中段的首句误当成题干。
     * 所以题库文档先按整篇拆题，再反查题落在哪一片（找不到就留空，题目照样能用）。</p>
     */
    public List<KbQuestionDraft> parse(String documentText, List<KbChunk> chunks) {
        if (documentText == null || documentText.isBlank()) {
            return parse(chunks);
        }
        String heading = chunks.isEmpty() || chunks.get(0).heading() == null
                ? "" : chunks.get(0).heading().strip();
        String body = stripHeading(documentText, heading);
        List<KbQuestionDraft> drafts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RawQuestion raw : rawQuestions(heading, body)) {
            if (drafts.size() >= MAX_QUESTIONS_PER_DOCUMENT) {
                break;
            }
            KbQuestionDraft draft = toDraft(raw, heading, body, locateChunk(cleanStem(raw.stem()), chunks));
            if (draft == null) {
                continue;
            }
            if (seen.add(com.panel.knowledge.domain.model.KbQuestion.fingerprint(draft.stem()))) {
                drafts.add(draft);
            }
        }
        return drafts;
    }

    /** 题号拆题 + 文档级兜底：整篇没有题号时才退化成"一问一答"。 */
    private List<RawQuestion> rawQuestions(String heading, String body) {
        List<RawQuestion> raws = splitByMarkers(body);
        if (!raws.isEmpty()) {
            return raws;
        }
        // 文档标题/合集页：没有正文，标题也不像问题，不产出题目。
        if (body.isBlank() && !looksLikeQuestion(heading)) {
            return List.of();
        }
        if (CONTAINER_HEADING.matcher(heading).matches() && !looksLikeQuestion(firstLine(body))) {
            return List.of();
        }
        if (looksLikeQuestion(heading)) {
            return List.of(new RawQuestion(heading, body));
        }
        String first = firstLine(body);
        if (looksLikeQuestion(first)) {
            return List.of(new RawQuestion(first, removeFirstLine(body)));
        }
        return List.of(new RawQuestion(usableHeading(heading) ? heading : firstSentence(body), body));
    }

    private KbQuestionDraft toDraft(RawQuestion raw, String heading, String body, Long chunkId) {
        String stem = cleanStem(raw.stem());
        if (stem.length() < 4) {
            return null;
        }
        String combined = heading + "\n" + stem;
        // 标签只从这道题自己的段落里取：整篇的结尾标签行（#Java #Redis …）会被撒到每一道上，
        // 刷题时"消息队列"那道题带着 Java/并发/JVM，用户会以为标签错了。
        String tagSource = raw.answer() == null || raw.answer().isBlank() ? body : raw.answer();
        List<String> tags = detectTags(heading + "\n" + tagSource);
        String meta = combined + "\n" + String.join(" ", tags);
        return new KbQuestionDraft(
                chunkId,
                truncate(stem, MAX_STEM),
                truncate(cleanAnswer(raw.answer()), MAX_ANSWER),
                detectDifficulty(meta),
                detectCompany(meta),
                detectRole(meta),
                detectYear(meta),
                tags);
    }

    /** 题落在哪一片：整段命中优先，其次用题干前 16 字探一下，都没有就不挂切片。 */
    private static Long locateChunk(String stem, List<KbChunk> chunks) {
        if (stem == null || stem.isBlank() || chunks == null || chunks.isEmpty()) {
            return null;
        }
        String probe = stem.length() > 16 ? stem.substring(0, 16) : stem;
        for (KbChunk chunk : chunks) {
            String text = chunk.text();
            if (text != null && (text.contains(stem) || text.contains(probe))) {
                return chunk.getId();
            }
        }
        return null;
    }

    /**
     * 按标题/题号/前缀拆题。
     *
     * <p>文档里有 markdown 标题时，题目只认标题——这是最可靠的边界：
     * 面试笔记的题目都是 <code>## 3. MyBatis 中 # 和 $ 的区别</code>，
     * 答案里的有序列表 <code>1. 定义注解…</code> 没有标题标记，不会再被误判成题目。
     * 没有标题结构的纯文本才退回编号行判断，且要求题号接得上（1、2、3…）并且
     * 行前有空行（答案里的列表会从 1 重新计数，也紧贴上一行）。</p>
     *
     * <p>踩过的坑：只看"像不像疑问句"，于是「5. Java 数组常用方法」这种陈述式题号被漏掉，
     * 「MyBatis 中 # 和 $ 的区别」的答案一路吞进 AOP、数组、List 三节内容。</p>
     */
    private List<RawQuestion> splitByMarkers(String body) {
        List<RawQuestion> result = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return result;
        }
        String[] lines = body.replace("\r\n", "\n").split("\n");
        // 只有"标题本身就是题目"时，才专用标题拆题；否则退回编号行。
        // 否则一份把题目写成 "1. xxx" 的文档，会因为开头有一个文档大标题而全军覆没。
        boolean hasHeadings = Arrays.stream(lines).anyMatch(KbQuestionParser::isQuestionHeading);
        StringBuilder answer = new StringBuilder();
        String stem = null;
        int lastNumber = -1;
        String previous = null;
        for (String line : lines) {
            boolean separated = previous == null || previous.isBlank();
            String candidate = null;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String text = heading.group("text").strip();
                Matcher numbered = NUMBERED.matcher(text);
                if (numbered.matches()) {
                    candidate = numbered.group("text").strip();
                } else if (looksLikeQuestionTitle(text)) {
                    candidate = text;
                }
            } else if (!hasHeadings) {
                Matcher numbered = NUMBERED.matcher(line);
                if (numbered.matches()) {
                    int number = Integer.parseInt(numbered.group("num"));
                    String text = numbered.group("text").strip();
                    if (isNextQuestionNumber(number, lastNumber) && (separated || looksLikeQuestion(text))) {
                        candidate = text;
                        lastNumber = number;
                    }
                } else {
                    String prefixed = markerText(line);
                    if (prefixed != null && looksLikeQuestion(prefixed)) {
                        candidate = prefixed;
                    }
                }
            }
            if (candidate != null) {
                if (stem != null) {
                    result.add(new RawQuestion(stem, answer.toString()));
                }
                stem = candidate;
                answer.setLength(0);
            } else if (stem != null) {
                answer.append(line).append('\n');
            }
            previous = line;
        }
        if (stem != null) {
            result.add(new RawQuestion(stem, answer.toString()));
        }
        return result;
    }

    /** 题号接得上才算新题：1 → 2 → 3，允许小幅跳号；从 1 重来的是答案里的列表。 */
    private static boolean isNextQuestionNumber(int number, int lastNumber) {
        if (lastNumber < 0) {
            return number <= 3;
        }
        return number > lastNumber && number <= lastNumber + 3;
    }

    /** 这一行是标题，而且标题本身读起来像一道题（带题号或像疑问句）。 */
    private static boolean isQuestionHeading(String line) {
        Matcher heading = HEADING.matcher(line);
        if (!heading.matches()) {
            return false;
        }
        String text = heading.group("text").strip();
        return NUMBERED.matcher(text).matches() || looksLikeQuestionTitle(text);
    }

    /**
     * 标题级的"像不像题目"要比正文严格。
     *
     * <p>正文里出现"实现""作用"只是普通陈述；标题里出现「### 实现思路」这种就一定是小节目录，
     * 不是题目。所以要求：问号结尾，或者以疑问/讲解类词开头。</p>
     */
    private static boolean looksLikeQuestionTitle(String text) {
        String value = text.strip();
        if (value.isEmpty() || value.length() > 160) {
            return false;
        }
        if (value.endsWith("?") || value.endsWith("？")) {
            return true;
        }
        return value.matches("^(?:如何|为什么|为何|怎么|怎样|什么|哪些|是否|谈谈|说说|聊聊|介绍|讲解|描述|解释|对比|比较).*");
    }

    private static String markerText(String line) {
        Matcher prefixed = PREFIXED.matcher(line);
        if (prefixed.matches()) {
            return prefixed.group(1).strip();
        }
        Matcher numbered = NUMBERED.matcher(line);
        if (numbered.matches()) {
            return numbered.group("text").strip();
        }
        return null;
    }

    private static boolean looksLikeQuestion(String text) {
        if (text == null) {
            return false;
        }
        String value = text.strip();
        if (value.endsWith("?") || value.endsWith("？")) {
            return true;
        }
        if (value.length() > 160) {
            return false;
        }
        return value.matches(".*(什么|如何|为什么|怎么|哪些|是否|区别|原理|作用|优劣|场景|实现|谈谈|说说|介绍).*");
    }

    private static boolean usableHeading(String heading) {
        if (heading == null || heading.isBlank()) {
            return false;
        }
        String value = heading.strip();
        if (value.length() < 4 || value.length() > 160) {
            return false;
        }
        if (GENERIC_HEADINGS.contains(value)) {
            return false;
        }
        return looksLikeQuestion(value) || value.length() <= 80;
    }

    private static String firstLine(String body) {
        if (body == null) {
            return "";
        }
        for (String line : body.split("\n")) {
            String value = line.strip();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String removeFirstLine(String body) {
        if (body == null) {
            return "";
        }
        int index = body.indexOf('\n');
        return index < 0 ? "" : body.substring(index + 1).strip();
    }

    private static String firstSentence(String body) {
        if (body == null) {
            return "";
        }
        String value = body.strip();
        int cut = value.length();
        for (String mark : List.of("。", "\n", "？", "?", "；")) {
            int index = value.indexOf(mark);
            if (index > 0 && index < cut) {
                cut = index;
            }
        }
        String sentence = value.substring(0, Math.min(cut, 120)).strip();
        return sentence.replaceAll("^[#>*\\-\\s]+", "");
    }

    private static String stripHeading(String text, String heading) {
        if (text == null) {
            return "";
        }
        String value = text.strip();
        if (heading == null || heading.isBlank()) {
            return value;
        }
        String title = heading.strip();
        if (value.startsWith(title)) {
            value = value.substring(title.length()).strip();
        }
        return value;
    }

    private static String cleanStem(String stem) {
        if (stem == null) {
            return "";
        }
        return stem.replaceAll("^[#>*\\-\\s]+", "")
                .replaceAll("[*_`]+", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String cleanAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String value = answer.strip();
        value = value.replaceAll("(?m)^\\s*(?:参考)?答案\\s*[:：]\\s*", "");
        return value.isBlank() ? null : value;
    }

    private static String detectDifficulty(String text) {
        if (text.contains("困难") || text.contains("高级") || text.contains("HARD")) {
            return "HARD";
        }
        if (text.contains("中等") || text.contains("中级") || text.contains("MEDIUM")) {
            return "MEDIUM";
        }
        if (text.contains("简单") || text.contains("容易") || text.contains("初级") || text.contains("EASY")) {
            return "EASY";
        }
        return null;
    }

    private static String detectCompany(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String company : COMPANIES) {
            if (lower.contains(company.toLowerCase(Locale.ROOT))) {
                return company;
            }
        }
        return null;
    }

    private static String detectRole(String text) {
        for (String role : ROLES) {
            if (text.contains(role)) {
                return role;
            }
        }
        return null;
    }

    private static Integer detectYear(String text) {
        Matcher matcher = YEAR.matcher(text);
        if (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (year >= 1990 && year <= 2100) {
                return year;
            }
        }
        return null;
    }

    private static List<String> detectTags(String text) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher matcher = TAG.matcher(text);
        while (matcher.find() && tags.size() < 6) {
            tags.add(matcher.group(1));
        }
        for (String piece : text.split("[｜|·]")) {
            String value = piece.strip();
            if (value.length() >= 2 && value.length() <= 12
                    && !value.matches(".*(面试|一面|二面|三面|笔试题|答案|解析).*")
                    && !COMPANIES.contains(value)) {
                tags.add(value);
            }
            if (tags.size() >= 6) {
                break;
            }
        }
        return List.copyOf(tags);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record RawQuestion(String stem, String answer) {
    }
}
