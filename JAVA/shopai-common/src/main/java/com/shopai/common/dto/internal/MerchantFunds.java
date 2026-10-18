package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商家资金 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code merchants} 表的资金字段归属 shopai-merchant 服务。
 * 订单服务在冻结资金前需要读取当前可用余额做校验，原先直接查 merchants 表。
 * <p>
 * 拆分为「独立库」后改为 Feign 调用商家服务内部接口
 * （GET /api/merchant/internal/funds?merchantId=1），本 DTO 是该链路的返回结构。
 * 资金「变动」请使用 {@link MerchantFundsChangeRequest}（原子累加），
 * 本 DTO 仅用于只读展示与前置校验。
 */
@Data
public class MerchantFunds implements Serializable {

    /** 商家ID（merchants.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 累计营收 */
    private BigDecimal totalRevenue;

    /** 可用余额 */
    private BigDecimal availableBalance;

    /** 冻结余额 */
    private BigDecimal frozenBalance;
}
