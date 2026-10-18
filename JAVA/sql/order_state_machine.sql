-- ============================================================
-- 订单状态机 + 商家资产 + 售后相关 数据库变更
-- 2026-07-25
-- ============================================================

-- 1. 订单表新增字段：物流单号、售后相关、资金结算
ALTER TABLE orders
    ADD COLUMN tracking_number VARCHAR(64) DEFAULT NULL COMMENT '物流单号',
    ADD COLUMN tracking_company VARCHAR(32) DEFAULT NULL COMMENT '物流公司',
    ADD COLUMN refund_amount DECIMAL(12,2) DEFAULT NULL COMMENT '退款金额',
    ADD COLUMN refund_reason VARCHAR(512) DEFAULT NULL COMMENT '退款/退货原因',
    ADD COLUMN refund_type VARCHAR(16) DEFAULT NULL COMMENT '退款类型: refund(仅退款), return_refund(退货退款)',
    ADD COLUMN return_tracking_number VARCHAR(64) DEFAULT NULL COMMENT '退货物流单号（顾客填）',
    ADD COLUMN return_tracking_company VARCHAR(32) DEFAULT NULL COMMENT '退货物流公司（顾客填）',
    ADD COLUMN refund_requested_at DATETIME DEFAULT NULL COMMENT '退款申请时间',
    ADD COLUMN refund_resolved_at DATETIME DEFAULT NULL COMMENT '退款处理完成时间',
    ADD COLUMN pre_refund_status VARCHAR(16) DEFAULT NULL COMMENT '进入退款前的状态（拒绝退款时回退用）',
    ADD COLUMN settled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '资金是否已结算 0=未结算 1=已结算（已完成后才结算）',
    ADD COLUMN auto_received TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否系统自动签收 0=否 1=是',
    ADD COLUMN auto_completed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否系统自动完成 0=否 1=是';

-- 2. 商家表新增字段：总营收、可提现余额、冻结余额
ALTER TABLE merchants
    ADD COLUMN total_revenue DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '总营收（累计入账）',
    ADD COLUMN available_balance DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '可提现余额',
    ADD COLUMN frozen_balance DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '冻结余额（退款中/待结算）';

-- 3. 售后操作日志表（追溯每次状态变更和操作记录）
CREATE TABLE IF NOT EXISTS order_action_logs (
    id BIGINT NOT NULL COMMENT '雪花ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    action VARCHAR(32) NOT NULL COMMENT '操作类型: create/pay/cancel/ship/receive/complete/refund_request/refund_approve/refund_reject/force_cancel/force_refund/force_complete/auto_receive/auto_complete',
    operator_type VARCHAR(16) NOT NULL COMMENT '操作人类型: customer/merchant/admin/system',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人ID',
    detail TEXT DEFAULT NULL COMMENT '操作详情JSON',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单操作日志';
