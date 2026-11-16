CREATE TABLE `kb_chat_session` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL,
  `kb_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `title` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kb_chat_session_owner` (`kb_id`,`user_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `kb_chat_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL,
  `session_id` bigint(20) NOT NULL,
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `citations_json` longtext COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `stages_json` longtext COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `latency_ms` int(11) DEFAULT NULL,
  `sequence_no` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_chat_message_seq` (`session_id`,`sequence_no`),
  KEY `idx_kb_chat_message_session` (`session_id`,`id`),
  CONSTRAINT `fk_kb_chat_message_session` FOREIGN KEY (`session_id`) REFERENCES `kb_chat_session` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
