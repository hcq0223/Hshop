package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.CustomerIdPair;
import com.shopai.common.dto.internal.CustomerInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 客户服务 Feign 客户端（订单服务侧）
 * <p>
 * 微服务拆分说明：{@code customers} 表归属 shopai-user 服务。订单服务原先在
 * OrderController / CartController / AddressController 三处通过
 * {@code CustomerRefMapper.selectIdByUserId(userId)} 直查 customers 表，
 * 把网关下发的 X-User-Id（users.id）映射为业务用的 customerId；
 * 另外订单查询还通过 {@code JOIN customers} 取客户昵称。
 * <p>
 * 按「独立库」处理后，customers 表只有用户服务能访问，改为本 Feign 客户端调用
 * 用户服务的客户内部接口。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_USER,
        path = InternalApiPaths.CUSTOMER_INTERNAL,
        contextId = "order-customer",
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
    Result<List<CustomerIdPair>> listIdsByUserIds(@RequestParam("userIds") String userIds);

    /**
     * 批量按客户ID查询客户信息（昵称/头像）
     * <p>
     * 用途：订单索引视图需要冗余客户昵称（原为 SQL {@code JOIN customers}，见 OrderIndexAssembler）。
     *
     * @param ids 逗号分隔的客户ID列表，如 "1,2,3"
     * @return 客户信息列表
     */
    @GetMapping("/batch")
    Result<List<CustomerInfo>> listCustomers(@RequestParam("ids") String ids);
}
