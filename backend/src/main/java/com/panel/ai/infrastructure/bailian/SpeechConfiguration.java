package com.panel.ai.infrastructure.bailian;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SpeechProperties.class)
public class SpeechConfiguration {
}
