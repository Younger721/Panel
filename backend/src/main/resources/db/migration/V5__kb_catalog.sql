-- P0: 知识目录树 + 条目归属（见 docs/知识目录与自动归类-设计方案.md）
-- 每个 KB 一棵树；根节点 parent_id = 0；最多 3 级。

CREATE TABLE `kb_category` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `kb_id` bigint(20) NOT NULL,
  `parent_id` bigint(20) NOT NULL DEFAULT 0,
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `path` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `path_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `depth` tinyint(4) NOT NULL,
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sort_no` int(11) NOT NULL DEFAULT 0,
  `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MANUAL',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_category_parent_name` (`kb_id`,`parent_id`,`name`),
  UNIQUE KEY `uk_kb_category_path_key` (`kb_id`,`path_key`),
  KEY `idx_kb_category_path` (`kb_id`,`path`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `kb_item_category` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `kb_id` bigint(20) NOT NULL,
  `item_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `item_id` bigint(20) NOT NULL,
  `category_id` bigint(20) NOT NULL,
  `is_primary` tinyint(1) NOT NULL DEFAULT 1,
  `confidence` decimal(4,3) DEFAULT NULL,
  `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MANUAL',
  `locked` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_item_category` (`item_type`,`item_id`,`category_id`),
  KEY `idx_kb_item_category_cat` (`category_id`,`item_type`),
  KEY `idx_kb_item_category_item` (`item_type`,`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
