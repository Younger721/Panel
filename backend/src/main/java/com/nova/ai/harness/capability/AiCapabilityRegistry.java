package com.nova.ai.harness.capability;

import com.nova.ai.harness.AiHarnessPlugin;
import com.nova.ai.harness.AiHarnessPluginRegistry;
import com.nova.shared.security.CurrentActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 能力清单（唯一工具目录）。
 * <p>内容来自两处：Spring 启动时注入的内置能力，以及远程 MCP 工具源在运行时注册的远程能力。
 * 注册与注销是并发安全的，遍历顺序按注册先后保持稳定。
 */
@Component
public class AiCapabilityRegistry {

    /** id → 能力实例；读多写少，用并发 Map */
    private final Map<String, AiCapability> byId = new ConcurrentHashMap<>();
    /** 注册顺序，保证遍历结果稳定（历史上依赖插入顺序的地方不受影响） */
    private final List<String> order = new CopyOnWriteArrayList<>();

    public AiCapabilityRegistry(List<AiCapability> discovered) {
        for (AiCapability capability : discovered) {
            putIfAbsent(capability);
        }
    }

    @Autowired
    public AiCapabilityRegistry(AiHarnessPluginRegistry plugins, List<AiCapability> discovered) {
        this(discovered);
        for (AiHarnessPlugin plugin : plugins.all()) {
            putIfAbsentLegacy(plugin);
        }
    }

    public Optional<AiCapability> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Collection<AiCapability> all() {
        return snapshot();
    }

    /** 运行时注册（远程 MCP 工具发现后调用）；同 id 以新实例覆盖，不改变顺序 */
    public void register(AiCapability capability) {
        if (capability == null || capability.descriptor() == null) {
            return;
        }
        String id = capability.descriptor().id();
        if (byId.put(id, capability) == null) {
            order.add(id);
        }
    }

    /** 运行时注销；不存在时静默返回 */
    public void unregister(String id) {
        if (id == null) {
            return;
        }
        if (byId.remove(id) != null) {
            order.remove(id);
        }
    }

    /**
     * 按身份与启用清单裁剪可用能力。
     * <p>启用清单为空时返回"默认能力集"：内置与内部工具，但<b>不含</b>远程能力——
     * 远程能力必须显式出现在启用清单里，避免登记一个外部源就等于全量放开。
     */
    public List<AiCapability> listFor(CurrentActor actor, Collection<String> enabledIds) {
        boolean explicit = enabledIds != null && !enabledIds.isEmpty();
        return snapshot().stream()
                .filter(capability -> explicit
                        ? enabledIds.contains(capability.descriptor().id())
                        : capability.descriptor().origin() != CapabilityOrigin.MCP_REMOTE)
                .filter(capability -> capability.supports(actor))
                .toList();
    }

    /** 指定来源下当前身份可见的能力（Harness 用它并入平台已启用的远程工具） */
    public List<AiCapability> listByOrigin(CurrentActor actor, CapabilityOrigin origin) {
        return snapshot().stream()
                .filter(capability -> capability.descriptor().origin() == origin)
                .filter(capability -> capability.supports(actor))
                .toList();
    }

    private List<AiCapability> snapshot() {
        List<AiCapability> result = new ArrayList<>(order.size());
        for (String id : order) {
            AiCapability capability = byId.get(id);
            if (capability != null) {
                result.add(capability);
            }
        }
        return result;
    }

    private void putIfAbsent(AiCapability capability) {
        if (capability == null || capability.descriptor() == null) {
            return;
        }
        String id = capability.descriptor().id();
        if (byId.putIfAbsent(id, capability) == null) {
            order.add(id);
        }
    }

    private void putIfAbsentLegacy(AiHarnessPlugin plugin) {
        if (plugin == null || plugin.descriptor() == null) {
            return;
        }
        String id = plugin.descriptor().id();
        if (byId.putIfAbsent(id, new LegacyAiHarnessCapabilityAdapter(plugin)) == null) {
            order.add(id);
        }
    }

    /** 供测试与诊断使用：当前登记的能力 id 快照 */
    List<String> ids() {
        return order.stream().filter(Objects::nonNull).toList();
    }
}
