-- 刷题闭环：一次抽题会话 + 每道题的作答记录；错题本/掌握度/复习权重全部由这两张表推导。
-- 为什么单独建表而不是复用 kb_chat_message：作答是结构化的（题目 → 结果 → 知识点），
-- 需要按 user × question × category 聚合，聊天记录里塞不下这些统计维度。

CREATE TABLE `kb_quiz_session` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `user_id` bigint(20) NOT NULL,
  `kb_id` bigint(20) NOT NULL,
  `question_count` int(11) NOT NULL,
  `correct_count` int(11) NOT NULL DEFAULT 0,
  `wrong_count` int(11) NOT NULL DEFAULT 0,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNNING',
  `started_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kb_quiz_session_user` (`user_id`,`kb_id`,`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `kb_quiz_attempt` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `session_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `kb_id` bigint(20) NOT NULL,
  `question_id` bigint(20) NOT NULL,
  `ordinal` int(11) NOT NULL,
  `result` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- 抽题时定格的知识点：题目以后被重新归类，历史错题本也不该跟着漂。
  `category_id` bigint(20) DEFAULT NULL,
  `shown_at` datetime(6) NOT NULL,
  `revealed_at` datetime(6) DEFAULT NULL,
  `answered_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_quiz_attempt_slot` (`session_id`,`ordinal`),
  KEY `idx_kb_quiz_attempt_user_q` (`user_id`,`question_id`,`created_at`),
  KEY `idx_kb_quiz_attempt_session` (`session_id`),
  KEY `idx_kb_quiz_attempt_cat` (`user_id`,`category_id`,`result`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 知识点之间的横切关系：树的边是“包含”，这张表的边是“相关/前置/对比”。
-- 只做 1 跳展示，不上实体级图谱（见 docs 讨论）。
CREATE TABLE `kb_category_relation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `kb_id` bigint(20) NOT NULL,
  `from_category_id` bigint(20) NOT NULL,
  `to_category_id` bigint(20) NOT NULL,
  `relation` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RELATED',
  `weight` decimal(3,2) NOT NULL DEFAULT 0.50,
  `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MANUAL',
  `note` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_category_relation` (`from_category_id`,`to_category_id`,`relation`),
  KEY `idx_kb_category_relation_from` (`kb_id`,`from_category_id`),
  KEY `idx_kb_category_relation_to` (`kb_id`,`to_category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
