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
 * 秒杀成功记录实体（对应 seckill_record 表）
 * <p>
 * <b>一人一单的最终兜底</b>：{@code (activity_id, user_id)} 上有唯一索引。
 * Redis 里的 {@code seckill:bought:{activityId}} Set 只是前置快速拦截
 * （挡掉 99% 的重复请求），真正防止「同一人抢到两次」靠的是这里的唯一索引 ——
 * Redis 可能因为主从切换、过期、被清空而丢数据，数据库约束不会。
 * <p>
 * 状态机：
 * <pre>
 *   pending ──订单创建成功──▶ success
 *      └────订单创建失败/超时──▶ failed（并回补库存）
 * </pre>
 */
@Data
@TableName("seckill_record")
public class SeckillRecord {

    /** 主键（雪花ID），同时作为本地消息表的幂等键 msgId */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 活动ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    /** seckill_stock.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    /** SKU ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 抢购用户ID（users.id，来自网关 X-User-Id 头） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客户ID（由用户服务映射所得，下单时用；映射失败时为 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    /** 抢购数量 */
    private Integer quantity;

    /** 成交秒杀价（下单时以此为准，不再回查商品服务，避免活动期间改价影响已抢订单） */
    private BigDecimal seckillPrice;

    /** 成单后回填的订单ID（shopai-order.orders.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 成单后回填的订单号 */
    private String orderNo;

    /** 状态：pending / success / failed / cancelled，取值见 {@link com.shopai.seckill.constant.SeckillRecordStatus} */
    private String status;

    /** 失败原因 */
    private String failReason;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** 抢购成功时间 */
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
