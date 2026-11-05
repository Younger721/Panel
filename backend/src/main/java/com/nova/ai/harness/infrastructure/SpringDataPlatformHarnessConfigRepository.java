package com.nova.ai.harness.infrastructure;

import com.nova.ai.harness.domain.PlatformHarnessConfig;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPlatformHarnessConfigRepository extends JpaRepository<PlatformHarnessConfig, Long> {
}
