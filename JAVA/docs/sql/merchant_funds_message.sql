-- ======================================================================
-- 订单服务：商家资金变更的本地消息表
--
-- 【为什么需要这张表】
-- 付款的本质是 orders.status = 'paid'，而「累加商家资金」是附带动作。
-- 但原实现把它放在付款事务里同步 Feign 调商家服务，导致一个结构性死结：
--
--   订单付款事务
--     ├─ UPDATE orders            （持有 orders 行的排他锁）
--     └─ Feign 调商家服务 ──────┐
--                                ↓
--                        商家服务 UPDATE merchants（要改同一行 merchants）
--                          └─ 若该行被占 → 等锁 → 50 秒后超时 → 返回 500
--                                ↓
--                        订单服务回滚（钱没加、订单也没改成 paid）
--
-- 关键点：付款事务持有数据库连接与行锁的整段时间，横跨了一次跨服务调用。
-- 只要商家服务那边锁被占住，用户就付不了款 —— 依赖方向是反的。
--
-- 改为：付款先在本地库提交（锁立刻释放），提交后再发资金变更；
-- 发起失败或对方失败时落本表，由定时任务重试到成功。
--
-- 【一致性保证】
--   唯一键 uk_merchant_biz (merchant_id, biz_key)：
--   同一笔业务（如 pay:{orderId}）只会有一条待处理消息，
--   重试天然幂等 —— 不会出现「重试一次、商户多收一次钱」。
-- ======================================================================

DROP TABLE IF EXISTS `merchant_funds_message`;
CREATE TABLE `merchant_funds_message` (
    `id`             BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `merchant_id`    BIGINT       NOT NULL COMMENT '商家ID',
    `biz_key`        VARCHAR(96)  NOT NULL
        COMMENT '业务幂等键，如 pay:{orderId} / refund_approve:{orderId}；与 merchant_id 共同唯一',
    `revenue_delta`  DECIMAL(10,2)         DEFAULT NULL COMMENT '累计营收增量，NULL=不变动',
    `available_delta` DECIMAL(10,2)        DEFAULT NULL COMMENT '可用余额增量，NULL=不变动',
    `frozen_delta`   DECIMAL(10,2)         DEFAULT NULL COMMENT '冻结余额增量，NULL=不变动',
    `reason`         VARCHAR(64)           DEFAULT NULL COMMENT '变动原因（日志与排查用）',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'pending'
        COMMENT '状态：pending待投递 / done已完成 / failed重试耗尽',
    `retry_count`    INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry`      INT          NOT NULL DEFAULT 10 COMMENT '最大重试次数',
    `next_retry_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可处理时间（指数退避）',
    `error_msg`      VARCHAR(512)          DEFAULT NULL COMMENT '最后一次失败原因',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 同一笔业务只允许一条消息：重试/重复落库都不会造成重复加钱
    UNIQUE KEY `uk_merchant_biz` (`merchant_id`, `biz_key`),
    -- 定时任务扫描用
    KEY `idx_status_retry` (`status`, `next_retry_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='商家资金变更本地消息表（付款与结算解耦）';
