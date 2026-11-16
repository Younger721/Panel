-- 知识点能力模型：一条 = 一个用户 × 一个知识点。
-- theta 是"能力值-难度"尺度上的盈亏（简化的 Elo/IRT 单参数版），答对一道该会的题加分少、答错一道不该错的题扣分多；
-- 面试记录以 2 倍权重写进同一张表（source=INTERVIEW），这样"刷题弱"和"面试弱"进同一套账。
CREATE TABLE `kb_skill_state` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `user_id` bigint(20) NOT NULL,
  `kb_id` bigint(20) NOT NULL,
  `category_id` bigint(20) NOT NULL,
  `theta` double NOT NULL DEFAULT 0,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `correct_count` int(11) NOT NULL DEFAULT 0,
  `wrong_count` int(11) NOT NULL DEFAULT 0,
  `unsure_count` int(11) NOT NULL DEFAULT 0,
  `interview_count` int(11) NOT NULL DEFAULT 0,
  `last_result` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'QUIZ',
  `last_seen_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_skill_state` (`user_id`,`kb_id`,`category_id`),
  KEY `idx_kb_skill_state_kb` (`kb_id`,`user_id`,`theta`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
