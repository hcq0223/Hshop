-- =============================================================================
-- orders.refund_seq：退款轮次（资金幂等键的一部分）
--
-- 背景（已发生的资损 bug）：
--   商家资金变更用本地消息表 + 唯一索引 (merchant_id, biz_key) 保证幂等，
--   biz_key 原为 `reason:orderId`，即「同一笔订单的同一种资金动作永久只记一次」。
--   但一笔订单可以合法地经历多轮退款：
--       申请(freeze) → 拒绝(refund_reject) → 再申请(freeze) → 同意(refund_approve)
--   第二轮的 freeze 因为 biz_key 与第一轮完全相同，被唯一索引静默吞掉，
--   而 refund_approve 照常执行 frozen_balance -= pay_amount，
--   结果 frozen_balance 被扣成负数。
--   实例：订单 2111474369843220482、商家 2078784861670813697，frozen_balance = -10.00。
--
-- 修复：biz_key 改为 `reason:orderId:refundSeq`。
--   refundSeq 每发起一次退款申请自增 1，于是「同一轮内幂等」与
--   「不同轮次各自记账」同时成立。
--
-- 本脚本为一次性 DDL（执行前请确认列不存在）。
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN refund_seq INT NOT NULL DEFAULT 0
        COMMENT 'refund cycle seq for funds idempotency key';
