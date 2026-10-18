package com.shopai.product.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.StockItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 库存服务 Feign 客户端
 * <p>
 * 微服务拆分说明：SKU 查询时需要展示实时库存，但库存已独立到 shopai-inventory 服务。
 * 商品服务通过本 Feign 客户端批量查询库存（GET /api/inventory/sku/batch?skuIds=1,2,3）。
 * 降级策略：库存服务不可用时返回空列表，商品详情不展示库存（见 InventoryClientFallback）。
 */
@FeignClient(
        name = "shopai-inventory",
        path = "/api/inventory",
        contextId = "product-inventory",
        fallbackFactory = InventoryClientFallback.class
)
public interface InventoryClient {

    /**
     * 批量查询 SKU 库存
     *
     * @param skuIds 逗号分隔的 SKU ID 列表，如 "1,2,3"
     * @return 库存列表（StockItem：skuId + stock）
     */
    @GetMapping("/sku/batch")
    List<StockItem> batchGetStock(@RequestParam("skuIds") String skuIds);

    @PostMapping("/init")
    Result<?> init(@RequestParam Long skuId, @RequestParam(defaultValue = "0") int initStock);
}
