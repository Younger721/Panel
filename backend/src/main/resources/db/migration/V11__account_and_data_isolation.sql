-- =====================================================================
-- V11 账号自助注册 + 用户级数据隔离
--
-- 目标：
--   1) 简历 / 面试 / 题库三块数据按“归属用户”隔离，候选人只能看到自己的；
--   2) 支撑邮箱验证码注册与注册风控（记录注册 IP、邮箱验证时间）。
--
-- 语义约定（重要，代码层按此实现）：
--   owner_user_id IS NULL  = 平台公共 / 历史遗留（迁移前只有管理员一个账号）
--   owner_user_id = N      = 该用户私有，只有 N 本人与平台管理员可见
--   简历、面试：NULL 只由平台管理员/历史数据产生
--   题库 kb_base：NULL = 内置公共题库（所有登录用户可读、不可写）
--
-- 兼容性：全部为“加列 / 加索引 / 加表”，不删列不改类型，
-- 旧版本 jar 在新库上仍可启动（expand 阶段），满足蓝绿发布要求。
-- =====================================================================

-- ---------- 1. 简历归属 ----------
ALTER TABLE `resume`
    ADD COLUMN `owner_user_id` BIGINT NULL AFTER `id`,
    ADD KEY `idx_resume_owner` (`owner_user_id`, `deleted_at`, `id`),
    ADD KEY `idx_resume_owner_hash` (`owner_user_id`, `content_hash`);

-- ---------- 2. 面试归属（冗余一列，避免每次 JOIN resume 才能过滤列表） ----------
ALTER TABLE `interview_session`
    ADD COLUMN `owner_user_id` BIGINT NULL AFTER `resume_id`,
    ADD KEY `idx_interview_owner` (`owner_user_id`, `created_at`);

-- ---------- 3. 题库归属 ----------
ALTER TABLE `kb_base`
    ADD COLUMN `owner_user_id` BIGINT NULL AFTER `id`,
    ADD KEY `idx_kb_base_owner` (`owner_user_id`, `kind`);

-- ---------- 4. 账号：邮箱验证 / 注册风控字段 ----------
ALTER TABLE `sys_user`
    ADD COLUMN `email_verified_at` DATETIME(6) NULL AFTER `normalized_email`,
    ADD COLUMN `register_ip` VARCHAR(64) NULL AFTER `token_version`,
    ADD COLUMN `register_source` VARCHAR(32) NULL AFTER `register_ip`,
    ADD KEY `idx_sys_user_register_ip` (`register_ip`, `created_at`);

-- ---------- 5. 邮箱验证码（只存哈希，验证码明文不进库） ----------
CREATE TABLE `sys_email_verification` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NULL,
    `email` VARCHAR(320) NOT NULL,
    `purpose` VARCHAR(24) NOT NULL DEFAULT 'SIGNUP',
    `code_hash` CHAR(64) NOT NULL,
    `attempts` INT NOT NULL DEFAULT 0,
    `expires_at` DATETIME(6) NOT NULL,
    `consumed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_email_verification_user` (`user_id`, `purpose`, `created_at`),
    KEY `idx_email_verification_lookup` (`email`, `code_hash`),
    KEY `idx_email_verification_gc` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---------- 6. 历史数据回填 ----------
-- 迁移前库里只有平台管理员/管理员一个业务账号，存量数据归给它；
-- 找不到任何租户账号时保持 NULL（视作平台数据，仅平台管理员可见）。
SET @legacy_owner := (
    SELECT u.id
      FROM `sys_user` u
     WHERE u.tenant_id IS NOT NULL
     ORDER BY u.id
     LIMIT 1
);

UPDATE `resume`
   SET `owner_user_id` = @legacy_owner
 WHERE `owner_user_id` IS NULL
   AND @legacy_owner IS NOT NULL;

UPDATE `interview_session` s
  JOIN `resume` r ON r.id = s.resume_id
   SET s.`owner_user_id` = r.`owner_user_id`
 WHERE s.`owner_user_id` IS NULL
   AND r.`owner_user_id` IS NOT NULL;

-- kb_base 故意不回填：内置题库（question-bank / tech-knowledge / job-knowledge）
-- 必须保持 NULL，才能对所有登录用户只读可见。
