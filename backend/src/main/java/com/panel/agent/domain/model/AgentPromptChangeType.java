package com.panel.agent.domain.model;

/** 门店提示词历史记录的变更类型 */
public enum AgentPromptChangeType {

    /** 门店管理员保存 */
    SAVE,

    /** 回滚到某个历史版本 */
    ROLLBACK,

    /** 平台应急清空 */
    CLEAR
}
