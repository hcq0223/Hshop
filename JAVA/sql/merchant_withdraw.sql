-- ============================================================
-- 商家提现记录表
-- 2026-07-26
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_withdraws (
    id BIGINT NOT NULL COMMENT '雪花ID',
    merchant_id BIGINT NOT NULL COMMENT '商家ID',
    amount DECIMAL(14,2) NOT NULL COMMENT '提现金额',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '状态: pending(待处理)/approved(已通过)/completed(已完成)/rejected(已拒绝)',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    updated_at DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '处理时间',
    PRIMARY KEY (id),
    INDEX idx_merchant_id (merchant_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家提现记录';
