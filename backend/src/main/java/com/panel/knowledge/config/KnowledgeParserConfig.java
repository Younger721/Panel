package com.panel.knowledge.config;

import com.panel.knowledge.infrastructure.parser.DocumentParserRegistry;
import com.panel.knowledge.infrastructure.parser.normalize.DocumentMarkdownNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeParserConfig {

    @Bean
    DocumentMarkdownNormalizer documentMarkdownNormalizer(
            @Value("${panel.knowledge.ingest.markdown-normalization.enabled:true}") boolean enabled) {
        return DocumentMarkdownNormalizer.create(enabled);
    }

    @Bean
    DocumentParserRegistry documentParserRegistry(DocumentMarkdownNormalizer markdownNormalizer) {
        return DocumentParserRegistry.withNormalizer(markdownNormalizer);
    }
}
