package com.shopai.common.dto.internal.order;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商家销售额排名 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：管理员仪表盘「商家销售额 Top/Bottom」原先由用户服务的 DashboardMapper
 * 用 {@code orders o INNER JOIN merchants m} 跨库联查出「店铺名 + 销售额」。
 * <p>
 * 拆分为「独立库」后无法再跨库 JOIN，改为两步组装：
 * 1. 订单服务按 orders 表本地聚合出「merchantId + 销售额」
 *    （GET /api/order/internal/dashboard/merchant-sales?limit=5&order=desc）；
 * 2. 用户服务再调商家服务批量补 shopName，在 Java 侧组装成前端需要的结构。
 * <p>
 * JSON 字段名与 SQL 别名一致（merchantId / totalSales），最终响应保持 totalSales/shopName。
 */
@Data
public class MerchantSalesStat implements Serializable {

    /** 商家ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 销售额（排除 pending_pay / cancelled） */
    private BigDecimal totalSales;
}
