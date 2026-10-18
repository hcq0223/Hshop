-- ============================================================
-- 评论系统升级：order_item_id → order_id + 商家评分联动 + 申诉机制
-- 执行前请备份 reviews 表数据
-- ============================================================

-- 1. 修改 reviews 表：order_item_id → order_id，新增 merchant_id
-- 先删除外键约束（约束名可能与 reviews_ibfk_1 不同，用 SHOW CREATE TABLE reviews 确认后替换）
ALTER TABLE reviews DROP FOREIGN KEY reviews_ibfk_1;

ALTER TABLE reviews
    DROP COLUMN order_item_id,
    ADD COLUMN order_id BIGINT NOT NULL AFTER id,
    ADD COLUMN merchant_id BIGINT NOT NULL AFTER product_id,
    ADD UNIQUE INDEX idx_order_id (order_id);

-- 2. 创建评论申诉表
CREATE TABLE IF NOT EXISTS review_complaints (
    id BIGINT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending / rejected / approved',
    admin_note VARCHAR(500),
    create_time DATETIME NOT NULL,
    update_time DATETIME,
    INDEX idx_review_id (review_id),
    INDEX idx_merchant_id (merchant_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. orders 表新增 is_reviewed 字段（防重复评价）
ALTER TABLE orders ADD COLUMN is_reviewed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已评价 0=未评价 1=已评价' AFTER auto_completed;
