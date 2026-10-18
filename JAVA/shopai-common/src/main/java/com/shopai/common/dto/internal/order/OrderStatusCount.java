package com.shopai.common.dto.internal.order;

import lombok.Data;

import java.io.Serializable;

/**
 * 订单状态分布 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：管理员仪表盘「订单状态分布」原先由用户服务的 DashboardMapper
 * 直查 orders 表（跨库聚合）。拆分为「独立库」后改为 Feign 调订单服务内部接口
 * （GET /api/order/internal/dashboard/status-distribution）。
 * <p>
 * JSON 字段名与 SQL 别名一致（status / count），前端无需改动。
 */
@Data
public class OrderStatusCount implements Serializable {

    /** 订单状态：pending_pay/paid/shipped/received/completed/cancelled/refunding */
    private String status;

    /** 该状态订单数 */
    private Long count;
}
