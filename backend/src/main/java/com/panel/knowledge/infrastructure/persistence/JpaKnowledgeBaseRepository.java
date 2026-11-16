package com.panel.knowledge.infrastructure.persistence;

import com.panel.knowledge.application.KnowledgeBaseRepository;
import com.panel.knowledge.domain.model.KnowledgeBase;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final SpringDataKnowledgeBaseRepository delegate;

    public JpaKnowledgeBaseRepository(SpringDataKnowledgeBaseRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public KnowledgeBase save(KnowledgeBase base) {
        return delegate.save(base);
    }

    @Override
    public Optional<KnowledgeBase> findById(Long id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<KnowledgeBase> findByCode(String code) {
        return delegate.findByCode(code);
    }

    @Override
    public List<KnowledgeBase> findAll() {
        return delegate.findAllByOrderByBuiltinDescIdAsc();
    }

    @Override
    public void delete(KnowledgeBase base) {
        delegate.delete(base);
    }
}
