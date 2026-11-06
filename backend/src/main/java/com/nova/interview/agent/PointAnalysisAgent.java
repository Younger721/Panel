package com.nova.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.ai.application.ChatModelGateway;
import com.nova.ai.domain.ContextSegment;
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

    public PointAnalysisAgent(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public List<PointDraft> analyze(String moduleTitle, String text) {
        int maxPoints = maxPointsFor(text);
        String prompt = INSTRUCTION.formatted(moduleTitle, maxPoints) + "\n原文：\n" + text;
        var result = gateway.completeStructuredJson(prompt,
                List.of(new ContextSegment("module", "module", text, moduleTitle, 1, "RESUME_MODULE")));
        JsonNode root = AgentJson.parse(objectMapper, result.content(), JsonNode.class, "PointAnalysis");

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
