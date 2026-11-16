package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.domain.model.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface SpringDataKnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByCode(String code);

    List<KnowledgeBase> findAllByOrderByBuiltinDescIdAsc();
}
