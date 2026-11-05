package com.nova.ai.harness.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.nova.iam.domain.model.RoleCode;

import java.util.Set;

/**
 * 能力描述符：模型工具定义与能力清单共用的唯一元数据。
 *
 * @param origin    能力来源；远程能力必须显式启用
 * @param timeoutMs 单次执行预算（毫秒），远程工具按此下发读取超时
 */
public record CapabilityDescriptor(
        String id,
        String name,
        String description,
        String type,
        String permission,
        boolean requiresTenant,
        Set<RoleCode> allowedRoles,
        JsonNode parameterSchema,
        CapabilityOrigin origin,
        long timeoutMs) {

    /** 默认单次执行预算（毫秒） */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    public CapabilityDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Capability id is required");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        type = type == null || type.isBlank() ? "READ" : type;
        allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
        parameterSchema = parameterSchema == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : parameterSchema;
        origin = origin == null ? CapabilityOrigin.LOCAL : origin;
        timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    /** 兼容既有调用：未显式声明来源的能力按内置能力处理 */
    public CapabilityDescriptor(String id, String name, String description, String type,
                                String permission, boolean requiresTenant, Set<RoleCode> allowedRoles,
                                JsonNode parameterSchema) {
        this(id, name, description, type, permission, requiresTenant, allowedRoles, parameterSchema,
                CapabilityOrigin.LOCAL, DEFAULT_TIMEOUT_MS);
    }
}
