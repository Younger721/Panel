-- =====================================================================
-- V13 简历 / 面试会话 / 问答轮次补乐观锁版本号
--
-- 背景：这三张表原先没有任何并发保护，而它们的写入路径天然是并发的：
--
--   resume           用户保存正文（整行 merge） ∥ 异步 worker 回写解析状态
--   interview_session 用户作答推进 ∥ 定时任务把超时会话收尾
--   interview_turn    认领先窄更新答案 ∥ 评分回写（读-改-写）
--
-- 后提交者会把先提交者的改动静默抹掉，数据库里看不出任何异常。加 @Version 后
-- 冲突方会拿到 OptimisticLockException，接口层转 409 让用户重试。
--
-- 注意：状态迁移走窄更新（只写 status/last_error/updated_at），不参与乐观锁，
-- 因为它本来就不会碰 objectKey/contentHash/editLayer。
--
-- 写成幂等的：MySQL DDL 不在事务里，一条失败会留在库里让迁移重跑时再撞一次。
-- =====================================================================

SET @add_resume_version := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'resume'
       AND COLUMN_NAME = 'version'
);
SET @ddl := IF(@add_resume_version = 0,
    'ALTER TABLE `resume` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_session_version := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'interview_session'
       AND COLUMN_NAME = 'version'
);
SET @ddl := IF(@add_session_version = 0,
    'ALTER TABLE `interview_session` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_turn_version := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'interview_turn'
       AND COLUMN_NAME = 'version'
);
SET @ddl := IF(@add_turn_version = 0,
    'ALTER TABLE `interview_turn` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
