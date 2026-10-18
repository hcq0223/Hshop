package com.shopai.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单流水实体（对应 seckill_order 表）
 * <p>
 * <b>真正的订单不在这里</b> —— 落在 shopai-order 的 {@code orders} 表。
 * 本表只记录「秒杀维度」的流水视图，目的是让秒杀服务能自己按活动/用户查询，
 * 不必为了一个活动报表去订单服务拉全量数据再过滤。
 * <p>
 * 数据来源：由 {@code SeckillMessageConsumer} 在收到订单服务返回的订单号后写入。
 */
@Data
@TableName("seckill_order")
public class SeckillOrder {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 活动ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    /** seckill_stock.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    /** seckill_record.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long recordId;

    /** 用户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    /** SKU ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 商品ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 商家ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 数量 */
    private Integer quantity;

    /** 秒杀单价 */
    private BigDecimal seckillPrice;

    /** 秒杀总金额 = seckillPrice × quantity */
    private BigDecimal totalAmount;

    /** shopai-order.orders.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 订单号 */
    private String orderNo;

    /** 收货地址ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long addressId;

    /** 状态：created / paid / cancelled / refunded */
    private String status;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
