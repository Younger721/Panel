package com.nova.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.ai.application.ChatModelCallResult;
import com.nova.ai.application.ChatModelGateway;
import com.nova.ai.domain.ContextSegment;
import com.nova.knowledge.infrastructure.parser.DocumentParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 简历原文 → 结构化画像。
 *
 * <p>两条链路刻意分开：<b>解析文本</b>用本地解析器（PDFBox/DOCX，确定性、零成本），
 * <b>抽取结构</b>才调模型。反过来让模型读 PDF 既贵又容易漏。</p>
 *
 * <p>模型输出必须在代码里再校验一次：先剥掉可能存在的 Markdown 代码块，
 * 再反序列化；失败抛异常让队列重试，绝不"尽力而为"地存半份脏数据。</p>
 */
@Service
public class ResumeParsingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeParsingService.class);

    private static final String INSTRUCTION = """
            你是简历解析器。请从给定简历原文中抽取结构化信息，严格按下面的 JSON 结构返回，
            不要输出 Markdown 代码块，不要输出任何解释文字。

            {
              "skills": ["技能名"],
              "yearsOfExperience": 数字（工作年限，无法判断填 null）,
              "projects": [{"name": "项目名", "role": "担任角色", "highlights": ["做了什么、结果如何"]}],
              "education": [{"school": "学校", "degree": "学历", "major": "专业", "period": "起止时间"}],
              "highlights": ["简历中最有说服力的 3-5 个亮点"],
              "summary": "一段不超过 120 字的候选人概述"
            }

            约束：
            1. 只抽取原文中真实存在的信息，禁止推测或补全。
            2. 原文没有的字段返回空数组或 null，不要编造。
            3. 技能名统一用业界通用写法（例如 Java、Spring Boot、MySQL、Redis）。
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;
    private final DocumentParserRegistry parsers = DocumentParserRegistry.standard();

    public ResumeParsingService(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /**
     * @param resumeId 仅用于给上下文分片一个稳定 id，便于日志定位
     * @return 结构化结果 + 归一化后的 JSON 原文（落库用）
     */
    public ParseOutcome parse(Long resumeId, String fileName, String contentType, byte[] bytes) {
        String text = parsers.parse(fileName, contentType, bytes);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("resume text is empty after parsing");
        }
        List<ContextSegment> context = List.of(
                new ContextSegment("resume-" + resumeId, String.valueOf(resumeId), text, fileName, 1, "RESUME"));
        ChatModelCallResult result = gateway.completeStructuredJson(INSTRUCTION, context);
        String json = extractJson(result.content());
        try {
            ParsedResume parsed = objectMapper.readValue(json, ParsedResume.class);
            String normalized = objectMapper.writeValueAsString(parsed);
            log.info("Resume parsed: id={}, skills={}, projects={}", resumeId,
                    parsed.skills().size(), parsed.projects().size());
            return new ParseOutcome(parsed, normalized);
        } catch (Exception ex) {
            throw new IllegalStateException("model returned unparsable resume JSON: " + abbreviate(json), ex);
        }
    }

    /** 模型偶尔会包一层 ```json，这里裁到最外层花括号。 */
    private static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("model returned empty content");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("model returned no JSON object: " + abbreviate(content));
        }
        return content.substring(start, end + 1);
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    public record ParseOutcome(ParsedResume parsed, String normalizedJson) {
    }
}
