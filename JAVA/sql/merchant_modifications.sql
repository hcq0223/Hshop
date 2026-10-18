-- =====================================================
-- ShopAi 商户信息修改申请表
-- 用于商家提交店铺信息/实名信息修改，管理员审核通过后才生效
-- =====================================================
CREATE TABLE IF NOT EXISTS `merchant_modifications` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `merchant_id` BIGINT NOT NULL COMMENT '商户ID（关联 merchants.id）',
    `shop_name` VARCHAR(100) NOT NULL COMMENT '店铺名称（当时快照，便于审核查看）',
    `type` VARCHAR(20) NOT NULL DEFAULT 'store' COMMENT '修改类型：store=店铺信息, realname=实名信息',
    `change_detail` TEXT NOT NULL COMMENT '修改项（JSON格式）',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态：pending=待审核, approved=已通过, rejected=已拒绝',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    PRIMARY KEY (`id`),
    KEY `idx_merchant_id` (`merchant_id`),
    KEY `idx_status` (`status`),
    KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户信息修改申请表';

-- 如果表已存在但缺少 type 列：
-- ALTER TABLE merchant_modifications ADD COLUMN `type` VARCHAR(20) NOT NULL DEFAULT 'store' COMMENT '修改类型' AFTER `shop_name`;

-- =====================================================
-- ShopAi 商户店铺轮播图
-- =====================================================
ALTER TABLE merchants ADD COLUMN `banners` TEXT NULL COMMENT '店铺轮播图（JSON数组字符串，如 ["url1","url2"]）' AFTER `shop_description`;
