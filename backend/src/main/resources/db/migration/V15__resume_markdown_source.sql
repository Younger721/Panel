-- =====================================================================
-- V15 简历支持 Markdown 原文（双源）
--
-- 背景：编辑器现在有两种编辑方式，它们要改的是同一份简历的不同侧：
--
--   原版排版编辑（PDF 原位）  改的是"看到的版面" → 走 source_* + edit_layer
--   Markdown 编辑             改的是"文本内容"   → 走这一对 md_source_*
--
-- 为什么不让 Markdown 直接覆盖 object_key/source_*：
--   1. 原文一旦变成 text/markdown，isPdf() 就是 false，原版预览、原位编辑、
--      「导出改后的 PDF」三条链路会同时失效——用户只是想改一段项目描述，
--      不该顺带丢掉整份简历的排版能力。
--   2. 两边语义不同：source_* 是"版式模板"，md_source_* 是"内容稿"。
--      解析按 md 优先、没有就回落 source_*，导出永远按 source_*。
--
-- 写入规则（ResumeService.saveContent）：
--   format=markdown → 只写 md_source_*，source_* 原样保留
--   format=text     → 写 object_key 的同时把 md_source_* 清空，
--                     否则下一次解析会读回旧 md，把用户刚存的正文又盖回去
--
-- 写成幂等的：MySQL DDL 不在事务里，一条失败会留在库里让迁移重跑时再撞一次。
-- =====================================================================

SET @add_md_key := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'resume'
       AND COLUMN_NAME = 'md_source_key'
);
SET @ddl := IF(@add_md_key = 0,
    'ALTER TABLE `resume` ADD COLUMN `md_source_key` VARCHAR(512) NULL',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_md_type := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'resume'
       AND COLUMN_NAME = 'md_source_content_type'
);
SET @ddl := IF(@add_md_type = 0,
    'ALTER TABLE `resume` ADD COLUMN `md_source_content_type` VARCHAR(128) NULL',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
