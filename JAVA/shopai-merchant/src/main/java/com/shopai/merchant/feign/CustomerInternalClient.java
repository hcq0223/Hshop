package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.CustomerIdPair;
import com.shopai.common.dto.internal.CustomerInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 客户内部接口 Feign 客户端（customers 表，商家服务侧）
 * <p>
 * 微服务拆分说明：{@code customers} 表归属 shopai-user 服务。商家服务原先直查该表：
 * - 评价发布时 userId → customerId 映射（原 {@code CustomerRefMapper.selectIdByUserId}）
 *   → {@link #listCustomerIds}
 * - 评价列表/详情联查评价人昵称头像（原 {@code ReviewMapper.xml} 的 {@code LEFT JOIN customers}）
 *   → {@link #listCustomers}
 * <p>
 * 安全说明：{@code /api/customer/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_USER,
        path = InternalApiPaths.CUSTOMER_INTERNAL,
        contextId = "merchant-customer",
        fallbackFactory = CustomerInternalClientFallback.class

)
public interface CustomerInternalClient {

    /**
     * 批量按 userId 查询客户ID映射（替代原 CustomerRefMapper.selectIdByUserId）
     *
     * @param userIds 逗号分隔的用户ID列表，如 "1"
     * @return 映射列表；未创建客户资料的用户 customerId 为 null
     */
    @GetMapping("/ids")
    Result<List<CustomerIdPair>> listCustomerIds(@RequestParam("userIds") String userIds);

    /**
     * 批量按客户ID查询客户信息（替代原 ReviewMapper.xml 的 LEFT JOIN customers）
     *
     * @param ids 逗号分隔的客户ID列表，如 "1,2,3"
     * @return 客户信息列表（昵称/头像）
     */
    @GetMapping("/batch")
    Result<List<CustomerInfo>> listCustomers(@RequestParam("ids") String ids);
}
