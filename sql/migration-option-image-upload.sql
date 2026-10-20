-- =====================================================================
-- 迁移：选项配图 + 上传文件登记
-- 性质：**加列 + 建表**（可重复执行：列已存在则跳过，表用 IF NOT EXISTS）
-- 回滚：
--   ALTER TABLE `t_option` DROP COLUMN `image_url`;
--   DROP TABLE `t_upload`;
--
-- ★ t_option.image_url：选项配图的相对路径（如 /uploads/xxx.webp），NULL = 无图。
--   存相对路径而不是绝对 URL：换域名 / 换对象存储时只改前缀，不改存量数据。
-- ★ t_upload：上传文件登记，给每日 GC 与容量审计用。
--   文件本身在 `app.upload.dir`（默认 ./uploads），不进 jar、不进 git。
-- =====================================================================

-- ① t_option.image_url
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_option'
              AND COLUMN_NAME = 'image_url');
SET @ddl = IF(@col = 0,
  'ALTER TABLE `t_option` ADD COLUMN `image_url` varchar(500) DEFAULT NULL COMMENT ''选项配图：相对路径 /uploads/xxx.webp；NULL=无图''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ② t_upload（上传登记）
CREATE TABLE IF NOT EXISTS `t_upload` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '上传ID',
  `url` varchar(500) NOT NULL COMMENT '相对路径 /uploads/xxx.webp（与 t_option.image_url 同口径）',
  `original_name` varchar(255) DEFAULT NULL COMMENT '原始文件名（只作审计，不参与存储路径）',
  `size_bytes` int NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `mime` varchar(100) DEFAULT NULL COMMENT '内容类型（按文件头探测，不信任请求头）',
  `created_by` int DEFAULT NULL COMMENT '上传人ID',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间（GC 宽限期从这里算）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_upload_url` (`url`),
  KEY `idx_upload_created` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传文件登记（GC 与容量审计）';

-- ---------- 自检 ----------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_option' AND COLUMN_NAME = 'image_url';

SELECT COUNT(*) AS upload_table_exists
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_upload';

SELECT COUNT(*) AS registered_files FROM `t_upload`;
