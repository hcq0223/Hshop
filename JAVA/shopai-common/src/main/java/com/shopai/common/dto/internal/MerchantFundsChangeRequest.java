package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商家资金变动请求 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code merchants} 表的资金字段（total_revenue / available_balance /
 * frozen_balance）归属 shopai-merchant 服务。订单服务原先在 payOrder / requestRefund /
 * approveRefund / forceCancel 等 8 处直接 {@code MerchantRefMapper.selectById + updateById}
 * 读改写这三个字段（跨库直读写）。
 * <p>
 * 拆分为「独立库」后，资金变动统一改为 Feign 调用商家服务内部接口
 * （POST /api/merchant/internal/funds/change），由商家服务在本地事务内原子完成，
 * 请求体即本 DTO。这样既消除了跨库写，也避免了「读-改-写」在并发下的资金覆盖问题。
 * <p>
 * 字段含义（允许为 null，null 表示本次不变动该字段；服务端按「累加」语义处理）：
 * - revenueDelta：total_revenue 增量（可为负）
 * - availableDelta：available_balance 增量（可为负）
 * - frozenDelta：frozen_balance 增量（可为负）
 */
@Data
public class MerchantFundsChangeRequest implements Serializable {

    /** 商家ID（merchants.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 累计营收增量（可为负） */
    private BigDecimal revenueDelta;

    /** 可用余额增量（可为负） */
    private BigDecimal availableDelta;

    /** 冻结余额增量（可为负） */
    private BigDecimal frozenDelta;

    /** 变动原因（仅用于日志与排查，如 pay / refund_request / refund_approve） */
    private String reason;

    public static MerchantFundsChangeRequest of(Long merchantId, String reason) {
        MerchantFundsChangeRequest r = new MerchantFundsChangeRequest();
        r.setMerchantId(merchantId);
        r.setReason(reason);
        return r;
    }

    public MerchantFundsChangeRequest revenue(BigDecimal delta) {
        this.revenueDelta = delta;
        return this;
    }

    public MerchantFundsChangeRequest available(BigDecimal delta) {
        this.availableDelta = delta;
        return this;
    }

    public MerchantFundsChangeRequest frozen(BigDecimal delta) {
        this.frozenDelta = delta;
        return this;
    }
}
