package com.shopai.user.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 商品服务 Feign 客户端（用户服务侧）
 * <p>
 * 微服务拆分说明：{@code products} 表归属 shopai-product 服务。用户服务管理员仪表盘原先通过
 * {@code DashboardMapper.countProducts} 跨库统计商品总数，按「独立库」处理后
 * products 表只有商品服务能访问，改为本 Feign 客户端调用商品服务内部计数接口。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_PRODUCT,
        path = InternalApiPaths.PRODUCT_INTERNAL,
        contextId = "user-product",
        fallbackFactory = ProductInternalClientFallback.class
)
public interface ProductInternalClient {

    /**
     * 统计商品总数（替代原 DashboardMapper.countProducts）
     */
    @GetMapping("/product-count")
    Result<Long> countProducts();
}
