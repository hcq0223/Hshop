package com.shopai.common.dto.internal.seckill;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀下单结果 DTO（跨服务契约）
 * <p>
 * 订单服务 {@code POST /api/order/internal/seckill/order} 的返回结构。
 * <p>
 * 为什么不直接返回订单实体：订单实体有 30 多个字段（退款、物流、结算……），
 * 秒杀服务只需要「订单ID + 订单号 + 金额 + 状态」这四项来回写自己的流水表。
 * 传整个实体既浪费带宽，也让秒杀服务无意间耦合到了订单内部的字段结构 ——
 * 订单服务以后加字段、改字段，都可能波及秒杀。
 */
@Data
public class SeckillOrderResult implements Serializable {

    /** 订单ID（orders.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 订单号 */
    private String orderNo;

    /** 下单客户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    /** 所属商家ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 实付金额（= 秒杀价 × 数量） */
    private BigDecimal payAmount;

    /** 订单状态：正常建单后为 pending_pay */
    private String status;

    /**
     * 是否命中了幂等返回（该订单号此前已创建过）。
     * <p>
     * 秒杀服务据此区分「本次真的建了单」与「重复投递命中了已有单」，
     * 前者才需要累加活动销量、写流水与发结果通知，后者只更新消息状态即可。
     * 少了这个标记，重复投递会把销量重复累加。
     */
    private Boolean idempotentHit;
}
