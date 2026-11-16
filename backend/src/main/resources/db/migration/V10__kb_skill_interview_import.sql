-- 面试信号回填台账：一条 = 一条 interview_turn 已经被折算进能力值。
-- 作用有两个：① 回填可重复执行但不会重复计数；② 出问题时能查出"这条能力值是被哪几次面试打下来的"。
CREATE TABLE `kb_skill_interview_import` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `turn_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `kb_id` bigint(20) NOT NULL,
  `category_id` bigint(20) NOT NULL,
  `skill` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `observation` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `score` int(11) DEFAULT NULL,
  `applied_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_skill_interview_import_turn` (`turn_id`),
  KEY `idx_kb_skill_interview_import_kb` (`kb_id`,`user_id`,`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
