package com.nova.knowledge.config;

import com.nova.knowledge.infrastructure.parser.DocumentParserRegistry;
import com.nova.knowledge.infrastructure.parser.normalize.DocumentMarkdownNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeParserConfig {

    @Bean
    DocumentMarkdownNormalizer documentMarkdownNormalizer(
            @Value("${nova.knowledge.ingest.markdown-normalization.enabled:true}") boolean enabled) {
        return DocumentMarkdownNormalizer.create(enabled);
    }

    @Bean
    DocumentParserRegistry documentParserRegistry(DocumentMarkdownNormalizer markdownNormalizer) {
        return DocumentParserRegistry.withNormalizer(markdownNormalizer);
    }
}
