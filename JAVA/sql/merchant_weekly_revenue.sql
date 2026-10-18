-- ============================================================
-- 商家周营收历史表
-- 2026-07-26
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_weekly_revenue (
    id BIGINT NOT NULL COMMENT '雪花ID',
    merchant_id BIGINT NOT NULL COMMENT '商户ID',
    week_start DATE NOT NULL COMMENT '周起始（周一）',
    week_end DATE NOT NULL COMMENT '周结束（周日）',
    total_revenue DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '本周营收',
    total_orders INT NOT NULL DEFAULT 0 COMMENT '本周订单数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (id),
    INDEX idx_merchant_id (merchant_id),
    INDEX idx_week_start (week_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家周营收历史';
