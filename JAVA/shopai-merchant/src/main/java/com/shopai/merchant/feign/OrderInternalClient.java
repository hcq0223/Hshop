package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.OrderInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务内部接口 Feign 客户端（商家服务侧）
 * <p>
 * 微服务拆分说明：{@code orders} 表归属 shopai-order 服务。商家服务原先通过
 * {@code OrderRefMapper} 跨库直查/直写 orders 表，共两处业务：
 * 1. 周营收归档：统计某商家在时间区间内的完成订单数（原 countCompletedOrders）
 *    → {@link #countCompletedOrders}
 * 2. 评价落库：二次校验订单是否已评价，并在落库后标记 is_reviewed（原 selectById + updateById）
 *    → {@link #getOrder} + {@link #markReviewed}
 * <p>
 * 安全说明：{@code /api/order/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_ORDER,
        path = InternalApiPaths.ORDER_INTERNAL,
        contextId = "merchant-order",
        fallbackFactory = OrderInternalClientFallback.class

)
public interface OrderInternalClient {

    /**
     * 按订单ID查询订单信息（替代原 OrderRefMapper.selectById）
     *
     * @param orderId 订单ID
     * @return 订单信息；不存在时 data 为 null
     */
    @GetMapping("/{orderId}")
    Result<OrderInfo> getOrder(@PathVariable("orderId") Long orderId);

    /**
     * 标记订单已评价（替代原 OrderRefMapper.updateById 写 is_reviewed = 1）
     *
     * @param orderId 订单ID
     * @return 影响行数
     */
    @PostMapping("/{orderId}/reviewed")
    Result<Integer> markReviewed(@PathVariable("orderId") Long orderId);

    /**
     * 统计某商家在时间区间内的完成订单数（替代原 OrderRefMapper.countCompletedOrders）
     *
     * @param merchantId 商家ID
     * @param statuses   逗号分隔的订单状态，如 "completed,received"
     * @param start      起始时间（含），格式 2026-10-05T00:00:00
     * @param end        结束时间（含），格式 2026-10-11T23:59:59
     * @return 订单数
     */
    @GetMapping("/count")
    Result<Integer> countCompletedOrders(@RequestParam("merchantId") Long merchantId,
                                         @RequestParam("statuses") String statuses,
                                         @RequestParam("start") String start,
                                         @RequestParam("end") String end);
}
