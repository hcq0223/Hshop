-- =============================================================================
-- 修复「提交商品评价把商家资金全部清零」
--
-- 事故（2026-10-18 00:47:36）：
--   顾客对订单 2111478149544472577 提交评价 →
--     ReviewServiceImpl.addReview
--       → afterCommit: updateMerchantRating(merchantId, 新平均分)
--         → MerchantServiceImpl.updateRating(merchantId, rating)
--           → Merchant m = new Merchant(); m.setId(id); m.setRating(rating);
--             merchantMapper.updateById(m);
--
--   Merchant 实体的 totalRevenue / availableBalance / frozenBalance 当时带有
--   `= BigDecimal.ZERO` 字段初值（不是 null）。MyBatis-Plus 的 updateById 使用
--   NOT_NULL 策略，只跳过 null、**不跳过 0**，于是这条 UPDATE 实际是：
--       SET rating = ?, total_revenue = 0, available_balance = 0, frozen_balance = 0
--   商家 2078784861670813697 的三项资金被一次性抹平。
--
--   之后 00:48:06 又有一条 freeze 消息（申请退款）落到这行被清零的数据上，
--   于是变成 total_revenue=0.00 / available_balance=-10.00 / frozen_balance=10.00。
--
-- 代码修复：
--   1) updateRating 改为 LambdaUpdateWrapper 只 SET rating 一列；
--   2) Merchant 三个资金字段去掉 ZERO 初值，改为 null，
--      使任何局部 updateById 都会跳过它们（插入仍由库的 DEFAULT 0.00 兜底）。
--
-- 数据修复依据：
--   frozen_balance 恒等于该商家所有 refunding 订单 pay_amount 之和（不变量，可重算）。
--   total_revenue / available_balance 无法由状态反推，故按事故前后差额补记：
--     清零前的真实值 = (total_revenue 177.95, available_balance 167.95, frozen 0.00)
--       —— 由 23:40 修复后的基线 167.95/167.95/0.00 加上 00:43:31 的 pay(+10 revenue)
--          与后续 freeze/reject 净额为 0 推出；
--     事故后实际值 = (0.00, -10.00, 10.00)。
--   校验：该商家无提现记录、无部分退款，故应满足 total_revenue = available + frozen。
-- =============================================================================

-- 1) frozen 用不变量重算（本次已等于 10.00，重算结果不变，保留以复位其它商家）
UPDATE merchants m
SET m.frozen_balance = COALESCE((
    SELECT SUM(o.pay_amount) FROM orders o
    WHERE o.merchant_id = m.id AND o.status = 'refunding'
), 0);

-- 2) 补回被清零的 revenue / available（相对量，已执行）
UPDATE merchants
SET total_revenue      = total_revenue      + 177.95,
    available_balance  = available_balance  + 177.95
WHERE id = 2078784861670813697;

-- 3) 校验：invariant_diff 应全为 0.00，frozen 应非负且等于 expected_frozen
SELECT m.id,
       m.shop_name,
       m.total_revenue,
       m.available_balance,
       m.frozen_balance,
       (m.total_revenue - m.available_balance - m.frozen_balance) AS invariant_diff,
       COALESCE((SELECT SUM(o.pay_amount) FROM orders o
                 WHERE o.merchant_id = m.id AND o.status = 'refunding'), 0) AS expected_frozen
FROM merchants m
ORDER BY m.id;
