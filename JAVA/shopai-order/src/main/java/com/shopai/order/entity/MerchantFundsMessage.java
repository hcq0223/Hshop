package com.shopai.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家资金变更本地消息（对应 merchant_funds_message 表）
 * <p>
 * <b>存在意义</b>：付款与结算解耦。付款只改本地 orders 并提交（行锁立刻释放），
 * 提交后再去通知商家服务改资金；通知失败就落本表，由定时任务重试到成功。
 * <p>
 * 这样做的直接收益：<b>商家服务那边锁被占住时，用户依然能付款成功</b>，
 * 资金到账从「阻塞在付款关键路径上」变成「最终一致（秒级~分钟级）」。
 * <p>
 * <b>幂等保证</b>：{@code (merchant_id, biz_key)} 上有唯一索引。
 * 同一笔业务（如 {@code pay:{orderId}}）只会有一条消息，重试不会重复加钱。
 */
@Data
@TableName("merchant_funds_message")
public class MerchantFundsMessage {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商家ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /**
     * 业务幂等键，形如 {@code pay:{orderId}} / {@code refund_approve:{orderId}}。
     * <p>
     * 与 {@code merchantId} 组成唯一索引：同一笔业务只允许一条消息。
     */
    private String bizKey;

    /** 累计营收增量；null 表示本次不变动该字段 */
    private BigDecimal revenueDelta;

    /** 可用余额增量；null 表示本次不变动该字段 */
    private BigDecimal availableDelta;

    /** 冻结余额增量；null 表示本次不变动该字段 */
    private BigDecimal frozenDelta;

    /** 变动原因（payment/refund_approve/...），仅用于日志与排查 */
    private String reason;

    /** 状态：pending待投递 / done已完成 / failed重试耗尽 */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /**
     * 下次可处理时间（指数退避）。
     * <p>
     * 不用固定间隔：商家服务短暂抖动时，固定 1 秒重试会在几秒内把次数耗光，
     * 然后被误判为失败。退避让间隔逐步拉长，给下游恢复的时间。
     */
    private LocalDateTime nextRetryAt;

    /** 最后一次失败原因 */
    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
