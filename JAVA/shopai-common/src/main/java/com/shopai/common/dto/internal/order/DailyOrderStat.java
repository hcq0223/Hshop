package com.shopai.common.dto.internal.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日订单汇总 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：管理员仪表盘「近N天每日订单」原先由用户服务的 DashboardMapper
 * 直查 orders 表（跨库聚合）。拆分为「独立库」后改为 Feign 调订单服务内部接口
 * （GET /api/order/internal/dashboard/daily?days=7）。
 * <p>
 * JSON 字段名与原 DashboardMapper.xml 的 SQL 别名保持一致
 * （date / count / amount），以确保前端仪表盘无需任何改动。
 */
@Data
public class DailyOrderStat implements Serializable {

    /** 日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** 当日订单数 */
    private Long count;

    /** 当日净营收（排除已取消订单） */
    private BigDecimal amount;
}
