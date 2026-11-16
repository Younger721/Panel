-- =====================================================================
-- V14 同一用户同一简历只能有一场「进行中」的面试
--
-- 背景：create 里先 findLatestInProgress 再插入，两个并发请求都查不到对方的
-- 记录，于是各建一场。实测并发两次「开始面试」得到两个 id（52、53），
-- 每次都是二次模型调用（JobFit + Planner），其中一场注定被丢掉。
--
-- 应用层再查一遍救不了并发窗口，只有数据库能保证。做法是生成列：
--
--   active_key = IN_PROGRESS 时 "owner-resume"，其它状态为 NULL
--
-- MySQL 唯一索引允许多个 NULL，所以"同时只能有一场进行中"与
-- "结束后可以再开新场"两个要求一次满足。实体不映射这一列，插入时由数据库算。
--
-- 写之前先把已经重复的进行中会话收敛掉（保留 id 最大的那条），否则加唯一索引会失败。
-- 全程幂等：MySQL DDL 不在事务里，重跑不能撞 Duplicate key name。
-- =====================================================================

-- 1) 收敛历史重复：同 (owner, resume) 有多场 IN_PROGRESS 时，只留最新那条
UPDATE `interview_session` s
  JOIN (
        SELECT owner_user_id, resume_id, MAX(id) AS keep_id
          FROM `interview_session`
         WHERE status = 'IN_PROGRESS'
         GROUP BY owner_user_id, resume_id
        HAVING COUNT(*) > 1
       ) d
    ON s.owner_user_id <=> d.owner_user_id
   AND s.resume_id = d.resume_id
   SET s.status = 'ABANDONED',
       s.updated_at = NOW(6)
 WHERE s.status = 'IN_PROGRESS'
   AND s.id <> d.keep_id;

-- 2) 生成列
SET @has_active_key := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'interview_session'
       AND COLUMN_NAME = 'active_key'
);
SET @ddl := IF(@has_active_key = 0,
    'ALTER TABLE `interview_session`
       ADD COLUMN `active_key` VARCHAR(80)
       GENERATED ALWAYS AS (
         CASE WHEN `status` = ''IN_PROGRESS''
              THEN CONCAT(COALESCE(`owner_user_id`, 0), ''-'', `resume_id`)
              ELSE NULL END
       ) STORED',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 唯一索引
SET @has_active_uk := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'interview_session'
       AND INDEX_NAME = 'uk_session_active'
);
SET @ddl := IF(@has_active_uk = 0,
    'ALTER TABLE `interview_session` ADD UNIQUE KEY `uk_session_active` (`active_key`)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
