package com.shopai.inventory.controller;

import com.shopai.common.Result;
import com.shopai.common.dto.StockItem;
import com.shopai.common.security.CurrentUser;
import com.shopai.inventory.service.InventoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 库存控制器（shopai-inventory 内部接口）
 * <p>
 * 微服务拆分说明：库存从商品服务独立出来，本控制器提供库存查询/扣减/回补/秒杀/预热接口。
 * 接口清单（见设计文档 5.6）：
 * - GET  /api/inventory/sku/{skuId}                查单个库存
 * - GET  /api/inventory/sku/batch?skuIds=1,2,3     批量查库存（商品服务 Feign 调用）
 * - POST /api/inventory/deduct                     扣减（乐观锁，订单服务 Seata 调用）
 * - POST /api/inventory/restore                    回补（订单取消/退款调用）
 * - POST /api/inventory/init                       SKU 创建时初始化
 * - POST /api/inventory/increase                   补货
 * - POST /api/inventory/seckill/preheat            秒杀库存预热（管理员）
 * - GET  /api/inventory/seckill/stock/{skuId}      秒杀剩余库存查询
 * - POST /api/inventory/seckill                    秒杀抢购
 * <p>
 * 安全说明：/api/inventory/** 不应被网关对外暴露（仅服务间 Feign 直连），
 * 网关路由需拦截此前缀（见设计文档第 6 节）。秒杀预热仍保留管理员校验兜底。
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    // ==================== 查询 ====================

    /**
     * 查询单个 SKU 库存
     */
    @GetMapping("/sku/{skuId}")
    public Result<?> getStock(@PathVariable Long skuId) {
        return Result.ok(inventoryService.getStock(skuId));
    }

    /**
     * 批量查询 SKU 库存（商品服务 Feign 调用，补 SKU 列表库存字段）
     *
     * @param skuIds 逗号分隔的 SKU ID，如 "1,2,3"
     */
    @GetMapping("/sku/batch")
    public List<StockItem> batchGetStock(@RequestParam("skuIds") String skuIds) {
        if (skuIds == null || skuIds.isBlank()) {
            return java.util.Collections.emptyList();
        }
        List<Long> ids = Arrays.stream(skuIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toList());
        return inventoryService.batchGetStock(ids);
    }

    // ==================== 扣减 / 回补 / 补货 / 初始化 ====================

    /**
     * 扣减库存（乐观锁防超卖）
     * <p>
     * 供订单服务 @GlobalTransactional 内通过 Feign 调用（Seata AT 分支事务）。
     * 请求参数：skuId + qty。
     */
    @PostMapping("/deduct")
    public Result<?> deduct(@RequestParam Long skuId, @RequestParam int qty) {
        boolean ok = inventoryService.deductStock(skuId, qty);
        if (ok) {
            return Result.ok("库存扣减成功");
        }
        return Result.error("库存不足或SKU不存在");
    }

    /**
     * 回补库存（订单取消/退款时调用）
     * <p>
     * 供订单服务 @GlobalTransactional 内通过 Feign 调用（Seata AT 分支事务）。
     * 请求参数：skuId + qty。
     */
    @PostMapping("/restore")
    public Result<?> restore(@RequestParam Long skuId, @RequestParam int qty) {
        inventoryService.restoreStock(skuId, qty);
        return Result.ok("库存回补成功");
    }

    /**
     * 初始化库存（SKU 创建时调用，幂等）
     * <p>
     * 请求参数：skuId + initStock。
     */
    @PostMapping("/init")
    public Result<?> init(@RequestParam Long skuId, @RequestParam(defaultValue = "0") int initStock) {
        inventoryService.initStock(skuId, initStock);
        return Result.ok("库存初始化成功");
    }

    /**
     * 增加可售库存（SKU 补货）
     * <p>
     * 请求参数：skuId + qty。
     */
    @PostMapping("/increase")
    public Result<?> increase(@RequestParam Long skuId, @RequestParam int qty) {
        inventoryService.increaseStock(skuId, qty);
        return Result.ok("库存增加成功");
    }

}
