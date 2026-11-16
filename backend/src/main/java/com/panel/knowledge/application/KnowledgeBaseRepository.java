package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KnowledgeBase;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase base);

    Optional<KnowledgeBase> findById(Long id);

    Optional<KnowledgeBase> findByCode(String code);

    List<KnowledgeBase> findAll();

    void delete(KnowledgeBase base);
}
