package com.shopai.seckill.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 抢购结果（前端轮询 {@code GET /api/seckill/result/{activityId}} 拿到的结构）
 * <p>
 * 抢购接口是「快速返回」的：只做完 Redis 扣减就立刻告诉用户「抢到了，正在生成订单」，
 * 真正的订单由 MQ 异步创建。所以前端需要轮询这里拿最终订单号。
 * <p>
 * <b>三种状态要分清楚，UI 表现完全不同</b>：
 * <ul>
 *   <li>{@code pending}：抢到了，订单还在生成 → 显示「排队中…」并继续轮询；</li>
 *   <li>{@code success}：订单已生成 → 显示订单号并跳转支付；</li>
 *   <li>{@code failed}：最终没抢到（库存不足/已抢过/下单失败）→ 显示失败原因。</li>
 * </ul>
 */
@Data
public class SeckillResultVO {

    /** 抢购结果状态：pending / success / failed */
    private String status;

    /** 活动ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    /** seckill_record.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long recordId;

    /** 成单后的订单ID（status=success 时才有） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 成单后的订单号（status=success 时才有） */
    private String orderNo;

    /** 秒杀成交价 */
    private BigDecimal seckillPrice;

    /** 数量 */
    private Integer quantity;

    /**
     * 提示文案 / 失败原因。
     * <p>
     * 失败时带上具体原因（库存不足 / 已抢过 / 超过限购 / 下单失败），
     * 而不是笼统的「抢购失败」—— 用户看到「您已参与过本活动」和
     * 「商品已抢完」的下一步动作是完全不同的。
     */
    private String message;

    /** 服务端时间，用于前端对齐（与活动详情同理） */
    private java.time.LocalDateTime serverTime;
}
