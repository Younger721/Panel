package com.panel.knowledge.infrastructure.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用真实笔记跑一遍解析器：这一批是用户 Obsidian 里的面试笔记，几乎每篇都带代码块。
 *
 * <p>其中「Orchestrator终止循环控制.md」有 40 行围栏。修围栏处理之前，这类文件一律
 * {@code document block text is required}。</p>
 *
 * <p>笔记目录不随仓库走，机器上没有就跳过，不影响 CI。</p>
 */
class RealNotesParseTest {

    private static final Path NOTES = Path.of(System.getProperty("user.home"), "..", "..", "learn", "obsidian", "面试")
            .normalize();
    private static final Path NOTES_FALLBACK = Path.of("D:", "learn", "obsidian", "面试");

    @Test
    void 用户笔记全部可解析且块非空() throws Exception {
        Path dir = Files.isDirectory(NOTES) ? NOTES : NOTES_FALLBACK;
        assumeTrue(Files.isDirectory(dir), "没有笔记目录，跳过");

        List<Path> notes;
        try (Stream<Path> walk = Files.walk(dir)) {
            notes = walk.filter(path -> path.toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        assumeTrue(!notes.isEmpty(), "目录里没有 md，跳过");

        DocumentParserRegistry registry = DocumentParserRegistry.standard();
        List<String> failures = new ArrayList<>();
        int totalBlocks = 0;
        int withFence = 0;

        for (Path note : notes) {
            String markdown = Files.readString(note, StandardCharsets.UTF_8);
            if (markdown.lines().anyMatch(line -> line.strip().startsWith("```"))) {
                withFence++;
            }
            try {
                ParsedDocument parsed = registry.parseStructured(note.getFileName().toString(),
                        "text/markdown", Files.readAllBytes(note));
                totalBlocks += parsed.blocks().size();
                if (parsed.blocks().isEmpty()) {
                    failures.add(note.getFileName() + "：解析出 0 个块");
                }
                parsed.blocks().forEach(block -> {
                    if (block.text().isBlank()) {
                        failures.add(note.getFileName() + "：出现空块");
                    }
                });
            } catch (RuntimeException ex) {
                failures.add(note.getFileName() + "：" + ex.getMessage());
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("文件 ").append(notes.size()).append(" 个（含代码块 ").append(withFence)
                .append(" 个），共 ").append(totalBlocks).append(" 块，失败 ").append(failures.size()).append(" 个\n");
        failures.forEach(line -> report.append("  ✗ ").append(line).append('\n'));
        System.out.println("[真实笔记解析] " + report);
        Files.writeString(Path.of("target", "real-notes-parse.txt"), report.toString(), StandardCharsets.UTF_8);

        assertThat(failures).as("解析失败的文件").isEmpty();
        assertThat(withFence).as("带代码块的文件数").isPositive();
    }
}
