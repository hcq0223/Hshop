package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 客户ID映射 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code customers} 表归属 shopai-user 服务。订单服务、商家服务原先通过
 * {@code CustomerRefMapper.selectIdByUserId(userId)} 直查 customers 表，把网关下发的
 * {@code X-User-Id}（users.id）映射为业务用的 customerId。
 * <p>
 * 拆分为「独立库」后，该映射改为 Feign 调用用户服务内部接口：
 * - 单条：GET /api/customer/internal/ids?userIds=1
 * - 批量：GET /api/customer/internal/ids?userIds=1,2,3
 * 本 DTO 承载 userId → customerId 的一对结果（batch 形式便于详情联查一次性补齐多个客户）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerIdPair implements Serializable {

    /** 用户ID（users.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客户ID（customers.id），该用户尚未创建客户资料时为 null */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;
}
