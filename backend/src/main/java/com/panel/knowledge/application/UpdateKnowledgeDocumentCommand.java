package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.DocumentVisibility;
import com.panel.iam.domain.model.RoleCode;
import java.util.List;

/** 更新知识库文档元数据（不含文件内容替换） */
public record UpdateKnowledgeDocumentCommand(DocumentVisibility visibility, List<RoleCode> audienceRoles) {
    public UpdateKnowledgeDocumentCommand(DocumentVisibility visibility) { this(visibility, null); }
}
