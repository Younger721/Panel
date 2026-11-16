package com.panel.ai.infrastructure.bailian;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.RealtimeAsrGateway;

@Configuration
@EnableConfigurationProperties(AsrProperties.class)
public class AsrConfiguration {

    @Bean
    RealtimeAsrGateway realtimeAsrGateway(AsrProperties properties,
                                          ObjectMapper objectMapper,
                                          @Value("${panel.ai.dashscope.api-key:}") String dashScopeApiKey) {
        return new BailianRealtimeAsrAdapter(properties, dashScopeApiKey, objectMapper);
    }
}
