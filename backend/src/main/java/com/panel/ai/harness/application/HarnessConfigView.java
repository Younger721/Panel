package com.panel.ai.harness.application;

import com.panel.ai.harness.AiHarnessPluginDescriptor;
import com.panel.ai.harness.capability.CapabilityDescriptor;

import java.util.List;

public record HarnessConfigView(
        List<String> enabledPlugins,
        List<AiHarnessPluginDescriptor> availablePlugins,
        String systemPrompt,
        boolean platformScope,
        List<CapabilityDescriptor> availableCapabilities) {
    public HarnessConfigView(List<String> enabledPlugins,
                             List<AiHarnessPluginDescriptor> availablePlugins,
                             String systemPrompt,
                             boolean platformScope) {
        this(enabledPlugins, availablePlugins, systemPrompt, platformScope, List.of());
    }
}
