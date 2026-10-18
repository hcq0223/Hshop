package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.index.OrderIndexItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 订单服务 Feign 客户端（搜索服务侧，order 索引回源）
 * <p>
 * 微服务拆分说明（列表查询改造）：搜索服务消费 {@code order.changed} 事件后，
 * 需要「回源」取得权威数据才能写 order 索引。索引视图（含店铺名、客户昵称、
 * 订单项摘要）由订单服务组装，因此搜索服务调本接口拉取，而不是自己去查数据库
 * （搜索服务改造后已不连数据库）。
 * <p>
 * 安全说明：{@code /api/order/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_ORDER,
        path = InternalApiPaths.ORDER_INTERNAL,
        contextId = "search-order",
        fallbackFactory = OrderSourceClientFallback.class
)
public interface OrderSourceClient {

    /**
     * 取单个订单的索引视图
     *
     * @param orderId 订单ID
     * @return 索引视图；订单不存在时 data 为 null
     */
    @GetMapping("/index/order/{orderId}")
    Result<OrderIndexItem> getIndexItem(@PathVariable("orderId") Long orderId);

    /**
     * 取全量订单索引视图（全量重建用）
     */
    @GetMapping("/index/orders")
    Result<List<OrderIndexItem>> listAllIndexItems();
}
