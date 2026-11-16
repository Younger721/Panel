package com.panel.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.infrastructure.ocr.ScannedResumeOcr;
import com.panel.knowledge.infrastructure.parser.DocumentParserRegistry;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
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
    private final ScannedResumeOcr scannedOcr;
    private final String modelName;

    public ResumeParsingService(ChatModelGateway gateway, ObjectMapper objectMapper,
                                ScannedResumeOcr scannedOcr,
                                com.panel.ai.infrastructure.bailian.DashScopeProperties dashScope) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.scannedOcr = scannedOcr;
        this.modelName = dashScope.getChatModel();
    }

    /**
     * @param resumeId 仅用于给上下文分片一个稳定 id，便于日志定位
     * @return 结构化结果 + 归一化后的 JSON 原文（落库用）
     */
    public ParseOutcome parse(Long resumeId, String fileName, String contentType, byte[] bytes) {
        com.panel.knowledge.infrastructure.parser.ParsedDocument document;
        try {
            document = parsers.parseStructured(fileName, contentType, bytes);
        } catch (RuntimeException ex) {
            // 扫描件的空内容不是"读到空字符串"，而是在 PDF→markdown 这一步就抛了
            // （DocumentMarkdownNormalizer: document conversion produced empty markdown）。
            // 不接住这个异常，下面的 isBlank 分支永远走不到——实测真实扫描件就是这么失败的。
            // 只认这一种失败：加密 PDF、损坏文件该照常失败，不该被当成扫描件去 OCR。
            if (!isEmptyTextFailure(ex)) {
                throw ex;
            }
            log.info("Resume has no text layer, falling back to OCR: id={}, file={}", resumeId, fileName);
            document = new com.panel.knowledge.infrastructure.parser.ParsedDocument(List.of());
        }
        String text = document.plainText();
        if (text == null || text.isBlank()) {
            // 扫描件 / 手机拍照转 PDF：文本层是空的。以前这里直接失败，
            // 而这类文件在求职者里很常见，所以先用 OCR 认一遍再决定。
            log.info("Resume has no text layer, falling back to OCR: id={}, file={}", resumeId, fileName);
            String recognized = scannedOcr.extract(bytes, fileName);
            if (recognized.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        scannedOcr.enabled()
                                ? "这份文件里提取不到文字，OCR 也没认出来。看起来是扫描件、图片，或者整页都是截图。"
                                  + "请上传可以直接选中文字的 PDF / Word 文件；如果只有照片，可以先转成文字再上传。"
                                : "这份文件里提取不到文字（看起来是扫描件或图片）。"
                                  + "当前环境没有开启 OCR，请改用可以直接选中文字的 PDF / Word 文件。");
            }
            text = recognized;
            // 用 markdown 再解析一遍：OCR 输出的 `#`/`-` 是有结构的，
            // 直接塞成一个 paragraph 会让模块切分只认出 1 个 INTRO 模块
            // （实测 2 页扫描件 -> 1 个模块 -> 只产 1 条批注）。
            // 按 markdown 解析能恢复标题层级，切出正常的模块数。
            try {
                document = parsers.parseStructured(fileName + ".md", "text/markdown",
                        recognized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                log.warn("OCR text could not be re-parsed as markdown, keeping it as one block: {}", ex.toString());
            }
            if (document.plainText().isBlank()) {
                document = new com.panel.knowledge.infrastructure.parser.ParsedDocument(
                        List.of(com.panel.knowledge.infrastructure.parser.DocumentBlock.paragraph(recognized)));
            }
            log.info("Scanned resume recognized by OCR: id={}, chars={}, blocks={}",
                    resumeId, text.length(), document.blocks().size());
        }
        List<ContextSegment> context = List.of(
                new ContextSegment("resume-" + resumeId, String.valueOf(resumeId), text, fileName, 1, "RESUME"));
        ChatModelCallResult result = gateway.completeStructuredJson(INSTRUCTION, context);
        String json = extractJson(result.content());
        try {
            ParsedResume parsed = objectMapper.readValue(json, ParsedResume.class);
            String normalized = objectMapper.writeValueAsString(parsed);
            log.info("Resume parsed: id={}, model={}, skills={}, projects={}", resumeId, modelName,
                    parsed.skills().size(), parsed.projects().size());
            return new ParseOutcome(parsed, normalized, modelName, document);
        } catch (Exception ex) {
            throw new IllegalStateException("model returned unparsable resume JSON: " + abbreviate(json), ex);
        }
    }

    private static boolean isEmptyTextFailure(RuntimeException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("empty markdown");
    }

    /** 模型偶尔会包一层 ```json，这里裁到最外层花括号。 */    private static String extractJson(String content) {
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

    public record ParseOutcome(ParsedResume parsed, String normalizedJson, String modelName,
                               com.panel.knowledge.infrastructure.parser.ParsedDocument document) {
    }
}
