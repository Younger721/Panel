package com.panel.knowledge.application;
import com.panel.knowledge.domain.model.*;
import com.panel.shared.error.*;
import com.panel.shared.security.CurrentActor;
import java.util.List;
public interface KnowledgeDocumentUseCase {
    KnowledgeDocumentView upload(CurrentActor actor, UploadKnowledgeDocumentCommand command);

    KnowledgeDocumentView find(CurrentActor actor, Long id);

    KnowledgeDocumentView update(CurrentActor actor, Long id, UpdateKnowledgeDocumentCommand command);

    KnowledgeDocumentView publish(CurrentActor actor, Long id, boolean published);

    KnowledgeDocumentView delete(CurrentActor actor, Long id);

    boolean canRead(CurrentActor actor, Long id);

    List<KnowledgeDocumentView> list(CurrentActor actor);

    /** 读取 MinIO 原文件字节（需管理权限） */
    byte[] readBinaryContent(CurrentActor actor, Long id);

    /** OnlyOffice 保存后覆盖原文件并触发重解析 */
    KnowledgeDocumentView replaceBinaryContent(CurrentActor actor, Long id, byte[] content);
}
