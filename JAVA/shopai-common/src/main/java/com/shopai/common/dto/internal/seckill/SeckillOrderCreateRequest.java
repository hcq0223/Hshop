package com.shopai.common.dto.internal.seckill;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀下单请求 DTO（跨服务契约）
 * <p>
 * 秒杀服务 → 订单服务的内部接口 {@code POST /api/order/internal/seckill/order} 的请求体。
 * <p>
 * 约定成 DTO 而不是 {@code Map<String,Object>}：这是一个跨服务的<b>内部契约</b>，
 * 字段名写错在 Map 里要等到运行时才炸（而且常表现为「参数为空」这种含糊错误），
 * 用具名 DTO 可以让编译期就发现不一致。
 * <p>
 * 为什么每个字段都要显式传：
 * <ul>
 *   <li>{@code customerId} —— 秒杀是异步调用，没有网关下发的 X-User-Id 头，
 *       身份必须由秒杀服务在抢购时解析好带过来（userId → customerId 由秒杀服务
 *       经用户服务 Feign 换取）；</li>
 *   <li>{@code seckillPrice} —— 必须传秒杀成交价。订单服务如果自己去查 SKU 日常价，
 *       用户抢到 9.9 的秒杀、订单金额却会变成原价；</li>
 *   <li>{@code orderNo} —— 幂等键。秒杀消息是至少一次投递，订单服务按此号查重，
 *       重复投递直接返回已有订单，避免重复下单。</li>
 * </ul>
 */
@Data
public class SeckillOrderCreateRequest implements Serializable {

    /** 抢购的客户ID（customers.id，非 users.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    /** SKU ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 数量 */
    private Integer quantity;

    /** 收货地址ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long addressId;

    /**
     * 秒杀成交单价。
     * <p>
     * 由秒杀服务传入，订单服务<b>不得</b>回查 SKU 日常价覆盖它 ——
     * 否则秒杀价形同虚设。
     */
    private BigDecimal seckillPrice;

    /**
     * 订单号（幂等键）。
     * <p>
     * 由秒杀服务按 {@code SK + seckillRecordId} 生成并固定不变，
     * 保证同一条消息重复投递时订单号相同。
     */
    private String orderNo;

    /** 秒杀抢购记录ID（仅用于日志追踪，订单服务不落库） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seckillRecordId;

    /** 活动ID（仅用于日志追踪） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    /**
     * 由秒杀抢购记录ID派生订单号（幂等键的统一生成规则）。
     * <p>
     * 前后必须用同一个规则：秒杀服务生成后存进本地消息表并原样重投，
     * 订单服务按同一个号查重。规则放在公共库，避免两边各写一份而漂移 ——
     * 一旦漂移，幂等就会失效，重复投递会真的创建出两笔订单。
     *
     * @param seckillRecordId seckill_record.id
     */
    public static String buildOrderNo(Long seckillRecordId) {
        return "SK" + seckillRecordId;
    }
}
