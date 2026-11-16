package com.panel.ai.harness.infrastructure;

import com.panel.ai.harness.domain.PlatformHarnessConfig;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPlatformHarnessConfigRepository extends JpaRepository<PlatformHarnessConfig, Long> {
}
