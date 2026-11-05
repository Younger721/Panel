package com.nova.iam.infrastructure.persistence;

import com.nova.iam.domain.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface SpringDataPermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByCode(String code);
}
