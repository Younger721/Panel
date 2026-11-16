-- P0: 题库结构化 + 共享概念层 + 图谱关系表（双库 RAG 架构，见 docs/双库RAG架构与存储设计.md）

CREATE TABLE `kb_question` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL,
  `kb_id` bigint(20) NOT NULL,
  `document_id` bigint(20) NOT NULL,
  `chunk_id` bigint(20) DEFAULT NULL,
  `stem` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `answer` longtext COLLATE utf8mb4_unicode_ci,
  `difficulty` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `company` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `role` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `question_year` int(11) DEFAULT NULL,
  `tags_json` text COLLATE utf8mb4_unicode_ci,
  `fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kb_question_kb` (`kb_id`,`difficulty`),
  KEY `idx_kb_question_doc` (`document_id`),
  KEY `idx_kb_question_chunk` (`chunk_id`),
  KEY `idx_kb_question_fingerprint` (`kb_id`,`fingerprint`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `kb_concept` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL,
  `canonical_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `normalized_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `aliases_json` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_concept_normalized` (`normalized_name`),
  KEY `idx_kb_concept_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `kb_question_concept` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `question_id` bigint(20) NOT NULL,
  `concept_id` bigint(20) NOT NULL,
  `weight` decimal(4,3) NOT NULL DEFAULT 1.000,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_question_concept` (`question_id`,`concept_id`),
  KEY `idx_kb_question_concept_concept` (`concept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `kb_graph_edge` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `kb_id` bigint(20) NOT NULL,
  `source_concept_id` bigint(20) NOT NULL,
  `target_concept_id` bigint(20) NOT NULL,
  `type` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `weight` decimal(4,3) NOT NULL,
  `evidence_chunk_id` bigint(20) DEFAULT NULL,
  `document_id` bigint(20) DEFAULT NULL,
  `extractor_version` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_graph_edge` (`kb_id`,`source_concept_id`,`target_concept_id`,`type`),
  KEY `idx_kb_graph_edge_source` (`source_concept_id`),
  KEY `idx_kb_graph_edge_target` (`target_concept_id`),
  KEY `idx_kb_graph_edge_doc` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `kb_graph_mention` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `concept_id` bigint(20) NOT NULL,
  `chunk_id` bigint(20) NOT NULL,
  `kb_id` bigint(20) NOT NULL,
  `start_offset` int(11) DEFAULT NULL,
  `end_offset` int(11) DEFAULT NULL,
  `quote` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_graph_mention` (`concept_id`,`chunk_id`,`start_offset`),
  KEY `idx_kb_graph_mention_chunk` (`chunk_id`),
  KEY `idx_kb_graph_mention_kb` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
