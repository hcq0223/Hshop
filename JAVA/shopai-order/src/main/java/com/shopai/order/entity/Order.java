package com.shopai.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单实体（对应 orders 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.Order 迁移至 shopai-order 服务，
 * 包名变更为 com.shopai.order.entity，XML namespace 同步更新。
 * customer / merchant 字段为联查填充（非数据库字段），
 * 拆分后联查仍指向 customers/merchants 表（共享单库阶段），后续拆独立库需改为 Feign 补字段。
 */
@Data
@TableName("orders")
public class Order {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    /** 按商户拆单，每笔订单归属一个商户 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private BigDecimal freightAmount;

    /** pending_pay/paid/shipped/received/completed/cancelled/refunding */
    private String status;

    /** 物流单号 */
    private String trackingNumber;

    /** 物流公司 */
    private String trackingCompany;

    /** 退款金额 */
    private BigDecimal refundAmount;

    /** 退款/退货原因 */
    private String refundReason;

    /** 退款类型: refund(仅退款), return_refund(退货退款) */
    private String refundType;

    /** 退货物流单号（顾客填） */
    private String returnTrackingNumber;

    /** 退货物流公司（顾客填） */
    private String returnTrackingCompany;

    /** 退款申请时间 */
    private LocalDateTime refundRequestedAt;

    /** 退款处理完成时间 */
    // ALWAYS：允许 updateById 把它显式写回 NULL。
    // 一轮退款被拒绝后再次申请时，必须清掉上一轮的处理时间，否则「退款中」的
    // 订单上会挂着一个旧的"已处理时间"。MyBatis-Plus 默认 NOT_NULL 策略会跳过
    // null 字段，setRefundResolvedAt(null) 就成了空操作。
    // （实体始终是先 selectById 再 updateById，不存在"局部更新"被误写成 NULL 的风险）
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime refundResolvedAt;

    /** 进入退款前的状态（拒绝退款时回退用） */
    private String preRefundStatus;

    /**
     * 退款轮次：每发起一次退款申请自增 1。
     * <p>
     * 用途：商家资金变更的幂等键原本是 {@code reason + ":" + orderId}，
     * 而 {@code (merchant_id, biz_key)} 是永久唯一索引 —— 一笔订单如果经历
     * 「申请 → 拒绝 → 再申请 → 同意」两轮退款，第二轮的 {@code freeze} 会因为
     * 幂等键重复被静默丢弃，而 {@code refund_approve} 照常扣减，
     * 于是 {@code frozen_balance} 被扣成负数。
     * 有了轮次后幂等键变为 {@code reason:orderId:refundSeq}，
     * 既保留「同一轮内重复请求不重复记账」的幂等性，又允许不同轮次各记各的。
     */
    private Integer refundSeq;

    /** 资金是否已结算 0=未结算 1=已结算 */
    private Integer settled;

    /** 是否系统自动签收 */
    private Integer autoReceived;

    /** 是否系统自动完成 */
    private Integer autoCompleted;

    /** 是否已评价 0=未评价 1=已评价 */
    @TableField("is_reviewed")
    private Integer isReviewed;

    /** 收货地址快照，防止地址变更影响历史订单 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> receiverSnapshot;

    private String paymentMethod;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime completedAt;

    @TableField("created_at")
    private LocalDateTime createTime;

    // ---- 关联对象 ----
    @TableField(exist = false)
    private List<OrderItem> orderItems;

    /** 联查填充的客户信息（非数据库字段，精简引用实体） */
    @TableField(exist = false)
    private CustomerRef customer;

    /** 联查填充的商户信息（非数据库字段，精简引用实体） */
    @TableField(exist = false)
    private MerchantRef merchant;
}
