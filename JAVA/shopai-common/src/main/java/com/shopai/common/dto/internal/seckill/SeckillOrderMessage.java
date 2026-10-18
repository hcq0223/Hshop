package com.shopai.common.dto.internal.seckill;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀异步下单消息（跨服务契约）
 * <p>
 * 秒杀服务在「Redis 扣减成功 + 本地消息表落库」之后，把本对象投到
 * {@code shopai.data.exchange} 的 {@code seckill.order.create} 路由键，
 * 由 <b>shopai-order</b> 的消费者取出并创建真实订单。
 * <p>
 * <b>为什么放在公共库而不是秒杀服务里</b>：这是<b>两端</b>共用的契约 ——
 * 生产端（秒杀服务）序列化它，消费端（订单服务）反序列化它。
 * 如果各写一个同名类：字段一旦不同步就会静默丢字段（比如新增 addressId 后
 * 消费端拿不到），且 Java 序列化按类全名匹配，包里多一个副本必然反序列化失败。
 * 放公共库可以让编译期就发现契约不一致。
 * <p>
 * <b>⚠️ serialVersionUID 必须显式声明</b>：JDK 序列化会把类结构算进默认 UID，
 * 类移动包路径 / 增删字段都会改变它，导致队列里的旧消息反序列化失败
 * （{@code InvalidClassException: local class incompatible}）。
 * 显式固定该值后，只要不删改已有字段的类型与含义，就向前兼容。
 */
@Data
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息业务ID（幂等键）= seckill_record.id。
     * <p>
     * 订单服务用它生成订单号（{@code SK + msgId}），从而「同一个 msgId
     * 无论投递几次都只会产生一笔订单」—— 幂等靠的是订单号的唯一性，
     * 而不是消费者自己记账，这样即使消费者重启、换实例也依然有效。
     */
    private String msgId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long recordId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 客户ID（customers.id）。
     * <p>
     * 必须随消息带过来：MQ 消费时没有用户请求上下文，拿不到网关的 X-User-Id，
     * 身份只能在抢购那一刻解析好。为 null 表示当时没解析出来，
     * 消费端会因此失败并触发重试（重试期间由秒杀侧补解析）。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private Integer quantity;

    /** 秒杀成交价：订单服务必须用它，不能回查 SKU 日常价 */
    private BigDecimal seckillPrice;

    /**
     * 收货地址ID。
     * <p>
     * 当前版本由抢购请求传入（用户在前端选好的地址）。如果业务上希望
     * 「抢到后再选地址」，可以把这个字段改成抢购时先不传、由用户在结果页补填，
     * 那需要额外一个「补地址触发下单」的接口 —— 属于后续演进，不在本框架内。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long addressId;
}
