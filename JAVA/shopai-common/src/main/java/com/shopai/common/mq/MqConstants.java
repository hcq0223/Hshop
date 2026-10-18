package com.shopai.common.mq;

/**
 * MQ 交换机/路由键/队列 常量
 * <p>
 * 微服务拆分说明：RabbitMQ 主题交换机 {@code shopai.data.exchange}，各业务服务为生产者，
 * shopai-search 为消费者。生产者把「数据变更事件」发到交换机，搜索服务消费后增量同步 ES。
 * 生产端与消费端共用本常量，避免路由键字符串在两处各写一份而漂移。
 * <p>
 * 路由键命名：{@code <entity>.changed}，另有 {@code <entity>.changed.delete} 专门表示删除。
 * 之所以把 delete 单独拆出一个路由键，是因为搜索服务用 topic 通配绑定
 * （{@code <entity>.changed.#}）即可同时订阅 upsert 与 delete，
 * 后续新增子类型无需改绑定关系。
 */
public final class MqConstants {

    /** 主题交换机名称 */
    public static final String DATA_EXCHANGE = "shopai.data.exchange";

    // ==================== 路由键：商品 ====================
    /** 商品变更（create / update 统一 upsert） */
    public static final String ROUTING_PRODUCT_CHANGED = "product.changed";
    /** 商品删除 */
    public static final String ROUTING_PRODUCT_DELETED = "product.changed.delete";

    // ==================== 路由键：商家 ====================
    /** 商家变更（create / update 统一 upsert） */
    public static final String ROUTING_MERCHANT_CHANGED = "merchant.changed";
    /** 商家删除 */
    public static final String ROUTING_MERCHANT_DELETED = "merchant.changed.delete";

    // ==================== 路由键：订单（列表查询改造新增） ====================
    /** 订单变更（创建 / 状态流转 / 退款等，统一 upsert） */
    public static final String ROUTING_ORDER_CHANGED = "order.changed";
    /** 订单删除 */
    public static final String ROUTING_ORDER_DELETED = "order.changed.delete";

    // ==================== 路由键：评价（列表查询改造新增） ====================
    /** 评价变更（新增 / 修改，统一 upsert） */
    public static final String ROUTING_REVIEW_CHANGED = "review.changed";
    /** 评价删除 */
    public static final String ROUTING_REVIEW_DELETED = "review.changed.delete";

    // ==================== 路由键：用户（列表查询改造新增） ====================
    /** 用户变更（注册 / 状态变更 / 资料变更，统一 upsert） */
    public static final String ROUTING_USER_CHANGED = "user.changed";
    /** 用户删除（销号） */
    public static final String ROUTING_USER_DELETED = "user.changed.delete";

    // ==================== 路由键：秒杀（秒杀服务新增） ====================
    /**
     * 秒杀抢购成功 → 异步创建订单。
     * <p>
     * 秒杀服务在「Redis 扣减成功」后把消息写入本地消息表，再由定时任务/直接投递
     * 发到本路由键；订单服务消费后创建真实订单并回传订单号。
     * <p>
     * 之所以不复用 {@code order.changed}：那条路由键的语义是「订单数据变了，去同步 ES 索引」，
     * 消费者是搜索服务；而这条的语义是「去创建订单」，消费者是订单服务。
     * 两者语义与消费方都不同，混用会让订单服务收到一堆索引同步事件。
     */
    public static final String ROUTING_SECKILL_ORDER_CREATE = "seckill.order.create";

    // ==================== 队列（搜索服务消费端声明） ====================
    public static final String QUEUE_PRODUCT_CHANGED = "shopai.search.product.changed.queue";
    public static final String QUEUE_MERCHANT_CHANGED = "shopai.search.merchant.changed.queue";
    public static final String QUEUE_ORDER_CHANGED = "shopai.search.order.changed.queue";
    public static final String QUEUE_REVIEW_CHANGED = "shopai.search.review.changed.queue";
    public static final String QUEUE_USER_CHANGED = "shopai.search.user.changed.queue";

    // ==================== 队列（秒杀异步下单） ====================
    /**
     * 秒杀异步创建订单队列（订单服务消费）。
     * <p>
     * 与索引同步队列分开：秒杀下单是**业务队列**，积压直接影响用户能否拿到订单，
     * 需要独立监控堆积量并按需扩容消费者；混在索引队列里排查困难。
     */
    public static final String QUEUE_SECKILL_ORDER_CREATE = "shopai.seckill.order.create.queue";

    /** 死信交换机 */
    public static final String DATA_DLX = "shopai.dlx.exchange";

    /** 死信路由键 */
    public static final String ROUTING_DLX = "shopai.dlx.routingkey";

    /** 死信队列 */
    public static final String QUEUE_DLX = "shopai.dlx.queue";


    private MqConstants() {
        // 工具类，禁止实例化
    }
}
