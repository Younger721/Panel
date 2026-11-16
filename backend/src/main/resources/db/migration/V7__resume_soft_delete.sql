-- 简历软删除：只打一个删除时间戳，不删行、不动对象存储，也不级联清画像/批注。
-- 好处有三个：误删能撤销；删掉的记录仍能解释"模型 token 花在哪"；重新上传同一份文件
-- 可以直接把原记录捞回来（content_hash 命中原行），不用再解析一遍。
ALTER TABLE `resume`
  ADD COLUMN `deleted_at` datetime(6) DEFAULT NULL AFTER `created_at`,
  ADD KEY `idx_resume_deleted_at` (`deleted_at`);
