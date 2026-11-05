package com.nova.ai.harness.application;

import com.nova.ai.harness.AiHarnessPluginDescriptor;
import com.nova.ai.harness.capability.CapabilityDescriptor;

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
