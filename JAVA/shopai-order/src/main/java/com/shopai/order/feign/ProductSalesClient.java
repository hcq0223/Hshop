package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 商品服务内部接口 Feign 客户端
 * <p>
 * 微服务拆分说明：原单体 OrderService 下单后调用 productMapper.incrementSalesCount
 * 累加商品销量。products 表拆分后归属 shopai-product 服务，订单服务不能直查对方表，
 * 故通过本 Feign 客户端调用商品服务内部接口（POST /api/product/internal/sales）。
 * <p>
 * 安全说明：/api/product/internal/** 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_PRODUCT,
        path = InternalApiPaths.PRODUCT_INTERNAL,
        contextId = "order-productsales",
        fallbackFactory = ProductSalesClientFallback.class
)
public interface ProductSalesClient {

    /**
     * 累加商品销量（下单成功后调用）
     * <p>
     * 原方法：ProductMapper.incrementSalesCount(productId, delta)。
     *
     * @param productId 商品ID
     * @param delta     增量（通常为购买数量）
     * @return 通用 Result
     */
    @PostMapping("/sales")
    Result<?> incrementSalesCount(@RequestParam("productId") Long productId,
                                  @RequestParam(value = "delta", defaultValue = "1") int delta);
}
