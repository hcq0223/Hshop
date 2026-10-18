package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code orders} 表归属 shopai-order 服务。商家服务原先通过
 * {@code OrderRefMapper} 直查/直写 orders 表，场景有两处：
 * 1. 周营收归档：统计某商家在时间区间内的完成订单数（countCompletedOrders）；
 * 2. 评价落库：校验订单归属并标记 orders.is_reviewed = 1。
 * <p>
 * 拆分为「独立库」后改为 Feign 调用订单服务内部接口
 * （GET /api/order/internal/count、POST /api/order/internal/reviewed），
 * 本 DTO 是该链路的统一返回结构。
 */
@Data
public class OrderInfo implements Serializable {

    /** 订单ID（orders.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 订单号（orders.order_no）。
     * <p>
     * 秒杀链路需要：秒杀服务用 {@code SK + recordId} 生成订单号做幂等，
     * 成单后要把订单号回写进 {@code seckill_record} / {@code seckill_order}
     * 供用户查询与对账。
     */
    private String orderNo;

    /** 下单客户ID（orders.customer_id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    /** 所属商家ID（orders.merchant_id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 订单总额 */
    private BigDecimal totalAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** pending_pay/paid/shipped/received/completed/cancelled/refunding */
    private String status;

    /** 是否已评价：1 已评价 / 0 未评价 */
    private Integer isReviewed;

    /** 下单时间 */
    private LocalDateTime createTime;
}
