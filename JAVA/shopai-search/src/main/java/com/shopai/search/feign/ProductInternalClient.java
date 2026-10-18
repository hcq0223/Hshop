package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.ProductIndexItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 Feign 客户端（搜索服务侧）
 * <p>
 * 微服务拆分说明：搜索服务原先通过自己的 {@code ProductRefMapper} 跨库直查 products，
 * 并 JOIN merchants/categories 组装可检索字段。按「独立库」处理后 products 归属
 * shopai-product 服务，商品服务负责在本地联查分类、Feign 调商家服务补店铺名/Logo，
 * 搜索服务只负责「拉取 + 写 ES」，故改为本 Feign 客户端。
 * <p>
 * 安全说明：{@code /api/product/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_PRODUCT,
        path = InternalApiPaths.PRODUCT_INTERNAL,
        contextId = "search-product",
        fallbackFactory = ProductInternalClientFallback.class
)
public interface ProductInternalClient {

    /**
     * 全量商品索引条目（替代原 ProductRefMapper.selectAllForIndex）
     * <p>
     * 供搜索服务全量重建 product 索引使用。
     */
    @GetMapping("/index/products")
    Result<List<ProductIndexItem>> listAllForIndex();

    /**
     * 按商品ID批量获取索引条目（替代原 ProductRefMapper.selectOneForIndex）
     * <p>
     * 供搜索服务 MQ 增量同步使用。返回空列表表示该商品已不存在，
     * 搜索服务据此从索引中删除对应文档。
     *
     * @param ids 逗号分隔的商品ID列表
     */
    @GetMapping("/index/product")
    Result<List<ProductIndexItem>> listByIdsForIndex(@RequestParam("ids") String ids);
}
