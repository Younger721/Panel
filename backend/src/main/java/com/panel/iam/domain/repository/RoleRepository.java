package com.panel.iam.domain.repository;

import com.panel.iam.domain.model.Role;
import com.panel.iam.domain.model.RoleCode;
import java.util.Optional;
import java.util.List;

public interface RoleRepository {
    Role save(Role role);
    Optional<Role> findByCode(RoleCode code);
    List<Role> findAll();
    long count();
}
