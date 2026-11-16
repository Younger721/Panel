package com.panel.knowledge.domain.model;

/** 问答消息角色：只区分提问与回答，检索过程以事件流形式回放，不落库。 */
public enum KbChatRole {
    USER,
    ASSISTANT
}
