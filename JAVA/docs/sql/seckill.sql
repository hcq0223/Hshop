-- ======================================================================
-- shopai-seckill 秒杀服务建表脚本
-- 数据库：hcq_shop（与其它服务同库；各服务只访问自己的表，不跨服务 JOIN）
--
-- 设计要点（已与需求确认）：
--   1. 秒杀库存**完全独立**，不与 shopai-inventory 的 inventory 表联动。
--      秒杀额度就是一份独立数字，卖完为止；主库存在常规下单链路上照常扣减。
--      ⚠️ 由此带来的副作用：同一个 SKU 的秒杀额度与常规库存互不知情，
--         常规下单可能把库存吃光、导致秒杀成功却无货可发。
--         运营侧需自行保证「秒杀额度 <= 该 SKU 留出的备货量」，
--         或后续把模型改成「活动创建时从主库存预占」。
--   2. SKU / 商品信息**不落库**，一律通过 Feign 调 shopai-product 内部接口实时取，
--      避免商品改价改名后秒杀侧出现脏数据。
--   3. 防超卖两层：Redis Lua 原子扣减（挡住绝大多数流量）
--      + seckill_stock 乐观锁扣减（最终落库，兜底）。
--   4. 限购：seckill_record 上 (activity_id, user_id, sku_id) 唯一索引兜底，
--      Redis Set 做前置快速拦截。
--   5. 异步下单：本地消息表 seckill_message 保证「扣减成功」与「订单创建」
--      最终一致，定时任务扫描重试 + 超时未成单自动回补库存。
-- ======================================================================

-- ----------------------------------------------------------------------
-- 1. 秒杀活动表
-- ----------------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_activity`;
CREATE TABLE `seckill_activity` (
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `title`         VARCHAR(128) NOT NULL COMMENT '活动名称',
    `merchant_id`   BIGINT       NOT NULL COMMENT '所属商家ID（商家只能管理自己的活动）',
    `start_time`    DATETIME     NOT NULL COMMENT '开抢时间',
    `end_time`      DATETIME     NOT NULL COMMENT '结束时间',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'draft'
        COMMENT '状态：draft草稿 / pending待开始 / running进行中 / ended已结束 / offline已下线',
    `per_user_limit` INT         NOT NULL DEFAULT 1 COMMENT '每人限购件数（0=不限）',
    `description`   VARCHAR(512)          DEFAULT NULL COMMENT '活动说明',
    -- 统计字段：预热/抢购过程中累加，避免每次都聚合统计
    `total_stock`   INT          NOT NULL DEFAULT 0 COMMENT '活动总库存（各SKU额度之和，冗余）',
    `sold_count`    INT          NOT NULL DEFAULT 0 COMMENT '已售件数',
    `version`       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_time` (`status`, `start_time`, `end_time`) COMMENT '定时任务扫描待开始/进行中/已结束的活动',
    KEY `idx_merchant` (`merchant_id`, `created_at`) COMMENT '商家后台列表'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀活动表';


-- ----------------------------------------------------------------------
-- 2. 秒杀商品库存表  ← 本次需求的主角
-- ----------------------------------------------------------------------
-- 一行 = 一个活动里的一个 SKU 的独立库存额度。
-- SKU 的规格（颜色/尺码/图片）与所属商品信息不在此表冗余，
-- 查询时由 SeckillInfoService 通过 Feign 调商品服务补齐。
DROP TABLE IF EXISTS `seckill_stock`;
CREATE TABLE `seckill_stock` (
    `id`             BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    `activity_id`    BIGINT        NOT NULL COMMENT '所属活动ID',
    `sku_id`         BIGINT        NOT NULL COMMENT 'SKU ID（product_skus.id，归属商品服务，此处只存ID）',
    `product_id`     BIGINT        NOT NULL COMMENT '商品ID（products.id，冗余存储便于按商品聚合，校验时与商品服务比对）',
    `merchant_id`    BIGINT        NOT NULL COMMENT '所属商家ID（从商品服务取得后冗余，用于权限与索引）',
    -- ---------- 独立额度 ----------
    `seckill_price`  DECIMAL(10,2) NOT NULL COMMENT '秒杀价（必须低于 SKU 日常价，创建时校验）',
    `origin_price`   DECIMAL(10,2) NOT NULL COMMENT '原价快照：创建活动时从商品服务取，仅用于页面展示划线价',
    `total_stock`    INT           NOT NULL COMMENT '本SKU秒杀总库存（独立额度，与 inventory 表无关）',
    `available_stock` INT          NOT NULL COMMENT '当前可抢库存（Redis 落地值，乐观锁扣减）',
    `locked_stock`   INT           NOT NULL DEFAULT 0 COMMENT '已扣减待成单的库存（MQ 消费成功/失败后归零或回补）',
    `sold_stock`     INT           NOT NULL DEFAULT 0 COMMENT '已成单库存',
    `version`        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（MyBatis-Plus @Version）',
    -- ---------- 限购 ----------
    `per_user_limit`  INT          NOT NULL DEFAULT 1 COMMENT '每人限购件数（冗余自活动，便于单独调某个SKU）',
    `sort`            INT          NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前）',
    -- ---------- 预热标记 ----------
    `preheated`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已预热到 Redis：0未预热 1已预热',
    `preheat_time`    DATETIME              DEFAULT NULL COMMENT '预热时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_activity_sku` (`activity_id`, `sku_id`) COMMENT '同一活动内一个SKU只能有一条额度记录',
    KEY `idx_sku` (`sku_id`) COMMENT '按SKU反查参与中的秒杀',
    KEY `idx_activity_sort` (`activity_id`, `sort`) COMMENT '活动详情页商品列表',
    KEY `idx_merchant` (`merchant_id`) COMMENT '商家后台'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀商品库存表（独立额度，不与主库存联动）';


-- ----------------------------------------------------------------------
-- 3. 秒杀成功记录表（活动内「每商品一人一单」的最终兜底）
-- ----------------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_record`;
CREATE TABLE `seckill_record` (
    `id`           BIGINT      NOT NULL COMMENT '主键（雪花ID）',
    `activity_id`  BIGINT      NOT NULL COMMENT '活动ID',
    `stock_id`     BIGINT      NOT NULL COMMENT 'seckill_stock.id',
    `sku_id`       BIGINT      NOT NULL COMMENT 'SKU ID',
    `user_id`      BIGINT      NOT NULL COMMENT '抢购用户ID（users.id，来自网关 X-User-Id）',
    `customer_id`  BIGINT               DEFAULT NULL COMMENT '客户ID（用户服务映射所得，下单时用）',
    `quantity`     INT         NOT NULL DEFAULT 1 COMMENT '抢购数量',
    `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '成交秒杀价',
    `order_id`     BIGINT               DEFAULT NULL COMMENT '成单后回填的订单ID',
    `order_no`     VARCHAR(64)          DEFAULT NULL COMMENT '成单后回填的订单号',
    `status`       VARCHAR(16) NOT NULL DEFAULT 'pending'
        COMMENT '状态：pending待成单 / success已成单 / failed成单失败 / cancelled已取消',
    `fail_reason`  VARCHAR(255)         DEFAULT NULL COMMENT '失败原因',
    `deleted`      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抢购成功时间',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 限购硬约束：并发下即使 Redis 拦截失效，这里也会挡住「同活动同商品」的第二次插入
    UNIQUE KEY `uk_activity_user_sku` (`activity_id`, `user_id`, `sku_id`) COMMENT '同一活动内每商品一人一单（不同商品互不影响）',
    KEY `idx_user` (`user_id`, `created_at`) COMMENT '我的秒杀记录',
    KEY `idx_stock` (`stock_id`) COMMENT '按额度记录查抢购明细',
    KEY `idx_status` (`status`, `created_at`) COMMENT '定时任务扫超时未成单的 pending 记录'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀成功记录表（活动内每商品一人一单）';


-- ----------------------------------------------------------------------
-- 4. 本地消息表（保证「Redis扣减成功」与「订单创建成功」最终一致）
-- ----------------------------------------------------------------------
-- 为什么不直接把「下单」放进 MQ 消费里就完事：
-- 秒杀扣减发生在 Redis（无事务），订单创建发生在订单服务（远程且可能失败）。
-- 中间任何一步失败，都会出现「库存扣了但没订单」。本地消息表把
-- 「扣减」与「待发消息」放进同一个本地事务，之后由定时任务不断重试投递，
-- 直到订单创建成功；超过最大重试次数则回补 Redis + DB 库存。
DROP TABLE IF EXISTS `seckill_message`;
CREATE TABLE `seckill_message` (
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `msg_id`        VARCHAR(64)  NOT NULL COMMENT '消息业务ID（幂等键，等于 seckill_record.id）',
    `activity_id`   BIGINT       NOT NULL COMMENT '活动ID',
    `stock_id`      BIGINT       NOT NULL COMMENT 'seckill_stock.id',
    `record_id`     BIGINT       NOT NULL COMMENT 'seckill_record.id',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `customer_id`   BIGINT                DEFAULT NULL COMMENT '客户ID',
    `sku_id`        BIGINT       NOT NULL COMMENT 'SKU ID',
    `quantity`      INT          NOT NULL DEFAULT 1 COMMENT '数量',
    `payload`       VARCHAR(1024)         DEFAULT NULL COMMENT '完整消息体JSON（重试时原样重投）',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'pending'
        COMMENT '状态：pending待投递 / sent已投递 / done已完成 / failed已失败放弃',
    `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry`     INT          NOT NULL DEFAULT 5 COMMENT '最大重试次数，超过则回补库存并置 failed',
    `next_retry_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可投递时间（指数退避）',
    `error_msg`     VARCHAR(512)          DEFAULT NULL COMMENT '最后一次失败原因',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_id` (`msg_id`) COMMENT '幂等：同一笔秒杀只产生一条待投递消息',
    KEY `idx_status_retry` (`status`, `next_retry_at`) COMMENT '定时任务扫描待投递/待重试消息'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀本地消息表（异步下单最终一致性）';


-- ----------------------------------------------------------------------
-- 5. 秒杀订单流水表
-- ----------------------------------------------------------------------
-- 说明：真正的订单落在 shopai-order 的 orders 表（shopping_type='seckill'）。
--       本表只记录「秒杀维度」的流水视图，便于秒杀服务自己按活动/用户查，
--       不必为了一个活动报表去跨服务拉全量订单。
DROP TABLE IF EXISTS `seckill_order`;
CREATE TABLE `seckill_order` (
    `id`            BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    `activity_id`   BIGINT        NOT NULL COMMENT '活动ID',
    `stock_id`      BIGINT        NOT NULL COMMENT 'seckill_stock.id',
    `record_id`     BIGINT        NOT NULL COMMENT 'seckill_record.id',
    `user_id`       BIGINT        NOT NULL COMMENT '用户ID',
    `customer_id`   BIGINT                 DEFAULT NULL COMMENT '客户ID',
    `sku_id`        BIGINT        NOT NULL COMMENT 'SKU ID',
    `product_id`    BIGINT        NOT NULL COMMENT '商品ID',
    `merchant_id`   BIGINT        NOT NULL COMMENT '商家ID',
    `quantity`      INT           NOT NULL DEFAULT 1 COMMENT '数量',
    `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀单价',
    `total_amount`  DECIMAL(10,2) NOT NULL COMMENT '秒杀总金额',
    -- 远程订单服务返回的真实订单
    `order_id`      BIGINT                 DEFAULT NULL COMMENT 'shopai-order.orders.id',
    `order_no`      VARCHAR(64)            DEFAULT NULL COMMENT '订单号',
    `address_id`    BIGINT                 DEFAULT NULL COMMENT '收货地址ID',
    `status`        VARCHAR(16)   NOT NULL DEFAULT 'created'
        COMMENT '状态：created已创建 / paid已支付 / cancelled已取消 / refunded已退款',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_record` (`record_id`) COMMENT '一笔秒杀记录只对应一条流水',
    KEY `idx_activity` (`activity_id`, `created_at`) COMMENT '活动订单列表',
    KEY `idx_user` (`user_id`, `created_at`) COMMENT '我的秒杀订单',
    KEY `idx_order` (`order_id`) COMMENT '按真实订单ID反查'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀订单流水表';


-- ======================================================================
-- Redis Key 规划（不建表，仅记录，实际由 SeckillRedisKeys 常量维护）
-- ======================================================================
-- seckill:stock:{stockId}            秒杀库存（String，Lua 原子扣减）
-- seckill:stock:preheat:{activityId} 预热状态标记
-- seckill:bought:{activityId}        已购记录（Hash，field=userId:skuId, value=已购件数；按活动内商品限购）
-- seckill:result:{activityId}:{userId} 抢购结果（String，前端轮询用）
-- seckill:path:{activityId}:{userId} 下单令牌（String，削峰用，可选）
