package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.product.ProductBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务内部接口 Feign 客户端（商家服务侧）
 * <p>
 * 微服务拆分说明：{@code products} 表归属 shopai-product 服务。商家服务的评价列表/详情
 * 原先在 {@code ReviewMapper.xml} 中用 {@code LEFT JOIN products p} 取商品名称。
 * 按「独立库」处理后改为本 Feign 客户端批量补齐商品名。
 * <p>
 * 安全说明：{@code /api/product/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_PRODUCT,
        path = InternalApiPaths.PRODUCT_INTERNAL,
        contextId = "merchant-product",
        fallbackFactory = ProductInternalClientFallback.class
)
public interface ProductInternalClient {

    /**
     * 按商品ID批量查询商品简要信息（替代原 ReviewMapper.xml 的 LEFT JOIN products）
     *
     * @param ids 逗号分隔的商品ID列表，如 "1,2,3"
     * @return 商品简要信息列表（id / name / mainImage）
     */
    @GetMapping("/product-briefs")
    Result<List<ProductBrief>> listProductBriefs(@RequestParam("ids") String ids);
}
