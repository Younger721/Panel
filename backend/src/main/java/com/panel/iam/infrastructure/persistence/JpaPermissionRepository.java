package com.panel.iam.infrastructure.persistence;

import com.panel.iam.domain.model.Permission;
import com.panel.iam.domain.repository.PermissionRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
class JpaPermissionRepository implements PermissionRepository {
    private final SpringDataPermissionRepository delegate;
    JpaPermissionRepository(SpringDataPermissionRepository delegate) { this.delegate = delegate; }
    public Permission save(Permission permission) { return delegate.save(permission); }
    public Optional<Permission> findByCode(String code) { return delegate.findByCode(code); }
    public List<Permission> findAll() { return delegate.findAll(); }
    public long count() { return delegate.count(); }
}
