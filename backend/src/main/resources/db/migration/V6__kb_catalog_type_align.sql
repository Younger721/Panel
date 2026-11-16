-- 对齐 Hibernate 校验：depth 用 int，布尔用 bit(1)
ALTER TABLE `kb_category`
  MODIFY COLUMN `depth` int(11) NOT NULL;

ALTER TABLE `kb_item_category`
  MODIFY COLUMN `is_primary` bit(1) NOT NULL DEFAULT b'1',
  MODIFY COLUMN `locked` bit(1) NOT NULL DEFAULT b'0';
