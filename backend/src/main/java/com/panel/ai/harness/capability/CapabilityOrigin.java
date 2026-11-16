package com.panel.ai.harness.capability;

/**
 * 能力来源，决定启用语义。
 * <p>远程能力必须显式启用，不吃"启用清单为空 = 全量"那一套，
 * 否则登记一个外部 MCP 源就等于对所有人放开。
 */
public enum CapabilityOrigin {

    /** 平台内置能力（Spring 启动时注入） */
    LOCAL,

    /** 由旧 Harness 插件适配而来的内部工具 */
    LEGACY,

    /** 远程 MCP server 发现的工具（运行时注册） */
    MCP_REMOTE
}
