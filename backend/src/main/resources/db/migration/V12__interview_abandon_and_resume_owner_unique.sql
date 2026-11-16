-- =====================================================================
-- V12 面试会话收尾状态 + 简历归属索引
--
-- 背景：V11 已给 resume / interview_session 加了 owner_user_id（用户级隔离），
-- 但代码一直接不上；本次把隔离打通后，又发现两个数据层缺口：
--
--   1) interview_session.status 只有 PLANNED/IN_PROGRESS/COMPLETED/FAILED，
--      没有"用户放弃 / 超时收尾"这一档，导致关掉浏览器就是永久 IN_PROGRESS。
--   2) 会话列表与上传去重都按 (owner, ...) 过滤，缺一个能直接用的索引。
--
-- 全部语句写成幂等形式：MySQL 的 DDL 不在事务里，一旦某条失败，
-- 已经执行的 ALTER 会留在库里，而 Flyway 会让整个迁移重跑——
-- 不做幂等判断的话，第二次一定会撞 "Duplicate key name"。
-- =====================================================================

-- 1) status 增加 ABANDONED
SET @has_abandoned := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'interview_session'
       AND COLUMN_NAME = 'status'
       AND COLUMN_TYPE LIKE '%ABANDONED%'
);
SET @ddl := IF(@has_abandoned = 0,
    'ALTER TABLE `interview_session` MODIFY COLUMN `status` '
        'ENUM(''PLANNED'',''IN_PROGRESS'',''COMPLETED'',''ABANDONED'',''FAILED'') NOT NULL',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 存量卡死的会话先归位：超过 24 小时没有任何推进的按放弃收尾，
--    免得历史页一直挂着一排"进行中"
UPDATE `interview_session`
   SET `status` = 'ABANDONED'
 WHERE `status` = 'IN_PROGRESS'
   AND `updated_at` < (NOW() - INTERVAL 24 HOUR);

-- 3) 简历归属索引：列表与去重都按 (owner, hash) 走。
--    这里刻意不做唯一约束：content_hash 会在编辑器保存时被改写
--    （saveContent 会把正文重存成新的 text/plain），同一用户把两份简历编辑成
--    同一段文字完全可能，撞唯一键会让保存接口直接 500。
SET @has_resume_index := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'resume'
       AND INDEX_NAME = 'idx_resume_owner_hash'
);
SET @ddl := IF(@has_resume_index = 0,
    'ALTER TABLE `resume` ADD KEY `idx_resume_owner_hash` (`owner_user_id`, `content_hash`)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) 会话列表按 (owner, status, created_at) 取
SET @has_session_index := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'interview_session'
       AND INDEX_NAME = 'idx_interview_owner_status'
);
SET @ddl := IF(@has_session_index = 0,
    'ALTER TABLE `interview_session` ADD KEY `idx_interview_owner_status` '
        '(`owner_user_id`, `status`, `created_at`)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
