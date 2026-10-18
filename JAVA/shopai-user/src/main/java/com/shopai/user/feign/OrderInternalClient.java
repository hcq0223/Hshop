package com.shopai.user.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.order.DailyOrderStat;
import com.shopai.common.dto.internal.order.MerchantSalesStat;
import com.shopai.common.dto.internal.order.OrderStatusCount;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 订单服务 Feign 客户端（用户服务侧）
 * <p>
 * 微服务拆分说明：{@code orders} 表归属 shopai-order 服务。用户服务管理员仪表盘原先通过
 * {@code DashboardMapper.xml} 跨库聚合 orders（订单总数、近7天每日订单、订单状态分布、
 * 商家销售额排名），按「独立库」处理后 orders 表只有订单服务能访问，
 * 故改为本 Feign 客户端调用订单服务内部仪表盘聚合接口。
 * <p>
 * 「商家销售额排名」的店铺名需要在用户服务侧再用
 * {@link MerchantInternalClient#listBriefs} 补齐（原为跨库 JOIN，现已拆成两步）。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_ORDER,
        path = InternalApiPaths.ORDER_INTERNAL,
        contextId = "user-order",
        fallbackFactory = OrderInternalClientFallback.class
)
public interface OrderInternalClient {

    /**
     * 统计订单总数（替代原 DashboardMapper.countOrders）
     */
    @GetMapping("/dashboard/order-count")
    Result<Long> countOrders();

    /**
     * 近N天每日订单汇总（替代原 DashboardMapper.dailyOrderStats）
     *
     * @param days 统计天数
     */
    @GetMapping("/dashboard/daily")
    Result<List<DailyOrderStat>> dailyOrderStats(@RequestParam("days") int days);

    /**
     * 按状态统计订单数（替代原 DashboardMapper.countGroupByStatus）
     */
    @GetMapping("/dashboard/status-distribution")
    Result<List<OrderStatusCount>> countGroupByStatus();

    /**
     * 商家销售额排名（替代原 DashboardMapper.top/bottomMerchantsBySales 的聚合部分）
     * <p>
     * 订单服务只负责按 orders 本地聚合「merchantId + 销售额」，
     * 店铺名由调用方再调商家服务补齐。
     *
     * @param limit 取前N条
     * @param order 排序方向：desc（Top，默认） / asc（Bottom）
     */
    @GetMapping("/dashboard/merchant-sales")
    Result<List<MerchantSalesStat>> merchantSalesRanking(@RequestParam("limit") int limit,
                                                         @RequestParam(value = "order", defaultValue = "desc") String order);
}
