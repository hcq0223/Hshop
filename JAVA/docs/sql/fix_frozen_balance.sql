-- =============================================================================
-- 修复「两轮退款共用幂等键」导致的冻结余额为负
--
-- 事故订单：2111474369843220482（商家 2078784861670813697 / 店铺 1111）
--   23:11:20  freeze               available -10.00, frozen +10.00
--   23:11:59  refund_reject        available +10.00, frozen -10.00   → 归零
--   23:2x:xx  freeze(第二轮)        幂等键重复，被唯一索引静默丢弃     → 没加回来
--   23:21:18  refund_approve       revenue  -10.00, frozen -10.00   → frozen = -10.00
--
-- 与「一轮正确退款」相比，实际账少了 available -10.00，多了 frozen -10.00：
--   正确净额： revenue -10.00, available -10.00, frozen 0
--   实际净额： revenue -10.00, available   0.00, frozen -10.00
-- 所以需要把 10.00 从 available 挪回 frozen。
--
-- 执行前请先备份 merchants 表。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 按不变量重算 frozen_balance（通用、可证明）
--
--    frozen_balance 恒等于该商家所有「进行中退款」订单的 pay_amount 之和。
--    这一列是纯派生数据，用不变量重算总是安全的。
-- -----------------------------------------------------------------------------
UPDATE merchants m
SET m.frozen_balance = COALESCE((
    SELECT SUM(o.pay_amount) FROM orders o
    WHERE o.merchant_id = m.id AND o.status = 'refunding'
), 0);

-- -----------------------------------------------------------------------------
-- 2) 补偿被多记在 available 上的那 10.00（仅针对本次事故商家）
--
--    上面第 1 步已把 frozen 修成 0，但事故同时让 available 多了 10.00。
--    available 无法由订单状态反推（还受提现影响），只能按事故金额补记。
--
--    金额校验（用另一个商家的独立不变量交叉验证）：
--      zxz 满足 total_revenue(225.00) = available(180.00) + frozen(45.00)。
--      事故商家 1111 修完 frozen 后应为 available = revenue - frozen
--                = 167.95 - 0 = 167.95，而当时是 177.95，正好多 10.00。
--
--    已于 2026-10-17 执行：1111  167.95 / 177.95 / -10.00  →  167.95 / 167.95 / 0.00
-- -----------------------------------------------------------------------------
-- UPDATE merchants
-- SET available_balance = available_balance - 10.00
-- WHERE id = 2078784861670813697;

-- -----------------------------------------------------------------------------
-- 3) 校验：frozen_balance 应全部非负，且等于 refunding 订单金额之和
-- -----------------------------------------------------------------------------
SELECT m.id,
       m.shop_name,
       m.available_balance,
       m.frozen_balance,
       COALESCE((SELECT SUM(o.pay_amount) FROM orders o
                 WHERE o.merchant_id = m.id AND o.status = 'refunding'), 0) AS expected_frozen
FROM merchants m
ORDER BY m.id;
