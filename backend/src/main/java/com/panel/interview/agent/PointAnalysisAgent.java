package com.panel.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.application.DefaultAiUsageService;
import com.panel.ai.domain.ContextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 逐模块把简历拆成"可被追问的小点"，并给出点评、优化建议与 3 个参考问题。
 *
 * <p>这里的产品定位是<b>简历顾问</b>而不是标准答案生成器：输出的是
 * "这段写得好不好、怎么改"，而不是让人直接背的示范答案。</p>
 */
@Component
public class PointAnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(PointAnalysisAgent.class);

    private static final int MAX_POINTS = 6;
    private static final int QUESTIONS_PER_POINT = 3;
    /** 单模块送给模型的原文上限（约 3000 token）：再长多半是被展开的表格或重复段落。 */
    private static final int MAX_MODULE_CHARS = 6_000;
    /** 单模块的调用次数上限（含首次）。 */
    private static final int MAX_ATTEMPTS = 2;
    /** 重试前的等待：给对端释放并发位留点时间。 */
    private static final long RETRY_BACKOFF_MS = 400;
    /** 限流时退避的上限：再久会让整份简历的解析卡到分钟级。 */
    private static final long MAX_BACKOFF_MS = 3_000;
    /** 用量记账的 feature 标签，和 ai_usage_record.feature 对齐 */
    private static final String RESUME_FEATURE = "RESUME_POINT";

    public record PointDraft(String title, String quote, String level, String comment,
                             List<String> improvements, List<String> questions) {
    }

    private static final String INSTRUCTION = """
            你是资深技术面试官兼简历顾问。下面是候选人简历中的「%s」部分。
            请把这段内容拆成若干个可被面试官单独追问的小点，逐点点评。

            只输出 JSON，不要 Markdown 代码块，不要解释：
            {"points":[{"title":"小点名","quote":"原文逐字片段","level":"STRONG|MEDIUM|WEAK",
              "comment":"这段写得好不好，具体指出问题","improvements":["怎么改","怎么改"],
              "questions":["问题1","问题2","问题3"]}]}

            约束：
            1. quote 必须从原文逐字复制（不改写、不省略），长度 10-60 字，作为批注锚点；
            2. 最多 %d 个小点，优先挑最值得追问的；
            3. level 标准：有量化结果或技术取舍=STRONG；只有职责罗列=MEDIUM；空泛或跑题=WEAK；
            4. comment 要具体到"缺了什么"，不要说"可以更详细"这类空话；
            5. 跳过姓名、电话、邮箱、性别、年龄等个人信息，这些不需要技术点评；
            6. questions 最多 3 个：技术点至少 1 个，非技术点可以给空数组，
               严禁用"（此小点无技术追问价值）"之类的占位文字凑数；
            7. 只分析给定文本，禁止编造原文没有的经历。
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;
    private final DefaultAiUsageService usage;

    public PointAnalysisAgent(ChatModelGateway gateway, ObjectMapper objectMapper, DefaultAiUsageService usage) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.usage = usage;
    }

    /**
     * 单模块批注。
     *
     * <p>失败分三档处理，共同点都是<b>不把整份简历拖成 FAILED</b>：</p>
     * <ul>
     *   <li>限流 / 连接被拒 / 超时 → 退避重试（限流按 400→800→1600ms 加倍，封顶 3 秒），
     *       还不行就跳过这个模块；</li>
     *   <li>返回的 JSON 读不出来 → 跳过这个模块；</li>
     *   <li>原文过长 → 先压到 {@link #MAX_MODULE_CHARS} 再发。</li>
     * </ul>
     *
     * <p>为什么要把「限流」和「内容不可用」分开：前者等一会儿就过，后者等多久都一样。
     * 混在一起处理的结果是——批量入库时被限流的模块被当成"没什么可批注的"悄悄丢掉，
     * 用户看到的是"这份简历批注很少"，而不是"模型侧被限流了"。</p>
     */
    public List<PointDraft> analyze(String moduleTitle, String text, Long ownerUserId) {
        // 先把原文压到可控长度：PDF 抽出来的「项目经历」动辄上万字（表格被展开成
        // 一行一段），整段塞进 prompt 会让请求体大到更容易在连接阶段被拒。
        String body = clamp(text, MAX_MODULE_CHARS);
        long backoff = RETRY_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return analyzeOnce(moduleTitle, body, ownerUserId);
            } catch (RuntimeException ex) {
                // 限流要退避得更久（×2，封顶 MAX_BACKOFF_MS）：原地立刻重试只会再撞一次墙。
                // 其它瞬时故障（连接重置、超时）保持固定 400ms。
                boolean transientFailure = isTransient(ex);
                if (attempt >= MAX_ATTEMPTS) {
                    // 走到这里说明重试也没成。这一块没有批注，但其余模块的批注和画像是完整的，
                    // 用户的简历仍然是可用的——比整份 FAILED 好得多。
                    log.error("Module '{}' 批注调用失败（{}，已重试 {} 次），跳过该模块：{}",
                            moduleTitle, transientFailure ? "限流或网络抖动" : "调用异常", attempt, ex.toString());
                    return List.of();
                }
                log.warn("Module '{}' 批注调用失败（第 {}/{} 次，{}），{}ms 后重试：{}",
                        moduleTitle, attempt, MAX_ATTEMPTS, transientFailure ? "限流或网络抖动" : "调用异常",
                        backoff, ex.toString());
                sleepQuietly(backoff);
                if (transientFailure) {
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                }
            }
        }
        return List.of();
    }

    private List<PointDraft> analyzeOnce(String moduleTitle, String body, Long ownerUserId) {
        int maxPoints = maxPointsFor(body);
        String prompt = INSTRUCTION.formatted(moduleTitle, maxPoints) + "\n原文：\n" + body;
        ChatModelCallResult result = gateway.completeStructuredJson(prompt,
                List.of(new ContextSegment("module", "module", body, moduleTitle, 1, "RESUME_MODULE")));
        // 后台线程没有请求上下文，用简历的 owner 记账：这是调用量最大的地方，
        // 不记的话成本看板里就没有简历分析这一块。
        usage.record(null, ownerUserId, result, RESUME_FEATURE);
        String content = result.content();
        JsonNode root;
        try {
            root = AgentJson.parse(objectMapper, content, JsonNode.class, "PointAnalysis");
        } catch (RuntimeException ex) {
            // 解析失败不重试：同样的输入大概率还是读不出来，白烧一次调用。
            log.warn("Module '{}' 批注解析失败，跳过该模块：{}", moduleTitle, ex.getMessage());
            return List.of();
        }

        List<PointDraft> points = new ArrayList<>();
        for (JsonNode node : root.path("points")) {
            String quote = node.path("quote").asText("").trim();
            String title = node.path("title").asText("").trim();
            if (quote.isBlank() || title.isBlank() || isPersonalContact(quote)) {
                continue;
            }
            points.add(new PointDraft(
                    title,
                    quote,
                    level(node.path("level").asText("")),
                    node.path("comment").asText("").trim(),
                    cap(strings(node.path("improvements")), 3),
                    cap(strings(node.path("questions")), QUESTIONS_PER_POINT)));
        }
        if (points.isEmpty()) {
            // 只有姓名/电话的模块（例如「个人介绍」）本来就该零批注：
            // 指令里明确要求跳过个人信息，这时候报错等于惩罚模型听了话。
            log.info("Module '{}' has nothing worth commenting on", moduleTitle);
            return List.of();
        }
        log.info("Module '{}' analyzed into {} points", moduleTitle, points.size());
        return cap(points, maxPoints);
    }

    /** 短模块不该产出 6 条批注：内容越少，可商榷的点越少。 */
    private static int maxPointsFor(String text) {
        int length = text == null ? 0 : text.length();
        if (length < 200) {
            return 2;
        }
        return length < 600 ? 4 : MAX_POINTS;
    }

    /** 尽量切在行尾，也别把 emoji 的代理对切成两半。 */
    private static String clamp(String text, int limit) {
        String value = text == null ? "" : text;
        if (value.length() <= limit) {
            return value;
        }
        int cut = limit;
        int newline = value.lastIndexOf('\n', limit);
        if (newline > limit / 2) {
            cut = newline;
        }
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut -= 1;
        }
        log.warn("Module text truncated before analysis: {} -> {} chars", value.length(), cut);
        return value.substring(0, cut);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 是不是"再来一次可能就好了"的错误。
     *
     * <p>限流和网络抖动都不该被当成"这个模块没内容可批注"——前者等一会儿就能过，
     * 后者是瞬时故障。真正该跳过的只有"调用成功但内容不可用"（JSON 读不出来），
     * 那条路径在 {@link #analyzeOnce} 里对解析失败直接返回空，根本不会走到这里。</p>
     */
    private static boolean isTransient(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many")
                    || lower.contains("throttl") || lower.contains("503") || lower.contains("502")
                    || lower.contains("connection refused") || lower.contains("connection reset")
                    || lower.contains("timeout") || lower.contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    /** 兜底过滤：即便模型没听指令，也不给联系方式生成批注。 */
    private static boolean isPersonalContact(String quote) {
        return quote.matches(".*(1[3-9]\\d{9}|@[\\w.-]+\\.\\w+).*");
    }

    private static String level(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "STRONG", "MEDIUM", "WEAK" -> value;
            default -> "MEDIUM";
        };
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            String value = node.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static <T> List<T> cap(List<T> values, int max) {
        return values.size() <= max ? values : List.copyOf(values.subList(0, max));
    }
}
