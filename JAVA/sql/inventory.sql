-- ============================================================
-- 库存表（微服务拆分：shopai-inventory 库存服务独占）
-- 2026-10-04
-- 说明：
--   原库存存放在 product_skus.stock 字段，拆分后由独立库存服务管理，
--   本表独立建表，为后续"库存独立数据库"铺路。
--   商品服务(shopai-product)不再读写 stock 字段。
-- ============================================================

CREATE TABLE IF NOT EXISTS inventory (
    id           BIGINT      NOT NULL COMMENT '主键(雪花ID)',
    sku_id       BIGINT      NOT NULL COMMENT '关联 product_skus.id',
    stock        INT         NOT NULL DEFAULT 0 COMMENT '当前可售库存',
    locked_stock INT         NOT NULL DEFAULT 0 COMMENT '锁定库存(秒杀/预扣用)',
    version      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at   DATETIME    DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表';
