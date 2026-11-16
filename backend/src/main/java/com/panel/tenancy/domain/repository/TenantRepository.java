package com.panel.tenancy.domain.repository;

import com.panel.tenancy.domain.model.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(Long id);

    Optional<Tenant> findByCode(String normalizedCode);

    boolean existsByCode(String normalizedCode);

    Page<Tenant> findAll(Pageable pageable);
}
