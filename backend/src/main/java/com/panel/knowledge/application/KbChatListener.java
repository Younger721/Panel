package com.panel.knowledge.application;

import java.util.List;

/** 问答流式回调；实现方负责把事件写成 SSE。 */
public interface KbChatListener {

    void onSession(Long sessionId, String title);

    void onStage(KbSearchStage stage);

    void onCitations(List<KbChatCitation> citations);

    void onToken(String token);

    void onDone(KbChatAnswer answer);
}
