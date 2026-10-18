package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 库存服务 Feign 客户端
 * <p>
 * 微服务拆分说明：原单体 OrderService 下单时调用 productSkuService.decreaseStockSync 扣库存、
 * 取消/退款时调用 restoreStockBySkuId 回补库存。库存独立为 shopai-inventory 服务后，
 * 订单服务通过本 Feign 客户端在 Seata 全局事务内调用库存扣减/回补接口。
 * <p>
 * 接口契约（见设计文档 5.6）：
 * - POST /api/inventory/deduct   扣减（乐观锁，返回库存不足时 Seata 回滚）
 * - POST /api/inventory/restore  回补（订单取消/退款调用）
 * <p>
 * 安全说明：/api/inventory/** 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_INVENTORY,
        path = InternalApiPaths.INVENTORY_INTERNAL,
        contextId = "order-inventory",
        fallbackFactory = InventoryClientFallback.class
)
public interface InventoryClient {

    /**
     * 扣减库存（Seata AT 分支事务参与方）
     * <p>
     * 原方法：ProductSkuService.decreaseStockSync(skuId, quantity) → boolean。
     * 拆分后：Feign 调 inventory.deduct，返回 Result（code=200 成功，code=500 库存不足）。
     *
     * @param skuId SKU ID
     * @param qty   扣减数量
     * @return 通用 Result（调用方检查 code 字段判断是否成功）
     */
    @PostMapping("/deduct")
    Result<?> deduct(@RequestParam("skuId") Long skuId, @RequestParam("qty") int qty);

    /**
     * 回补库存（订单取消/退款时调用）
     * <p>
     * 原方法：ProductSkuService.restoreStockBySkuId(skuId, quantity)。
     *
     * @param skuId SKU ID
     * @param qty   回补数量
     * @return 通用 Result
     */
    @PostMapping("/restore")
    Result<?> restore(@RequestParam("skuId") Long skuId, @RequestParam("qty") int qty);
}
