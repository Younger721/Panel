-- DB-backed async queue used only when panel.job-queue=mysql|kafka.
-- The default Redis Stream queue does not read this table.
CREATE TABLE IF NOT EXISTS `async_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `type` varchar(64) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL,
  `attempts` int NOT NULL DEFAULT 0,
  `next_attempt_at` timestamp(6) NOT NULL,
  `lease_until` timestamp(6) NULL,
  `last_error` varchar(1000) NULL,
  `created_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_async_job_claim` (`status`,`next_attempt_at`,`id`),
  KEY `idx_async_job_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
