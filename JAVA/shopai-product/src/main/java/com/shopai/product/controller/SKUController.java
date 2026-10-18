package com.shopai.product.controller;

import com.shopai.common.Result;
import com.shopai.common.dto.StockItem;
import com.shopai.common.security.CurrentUser;
import com.shopai.product.entity.ProductSku;
import com.shopai.product.feign.InventoryClient;
import com.shopai.product.service.ProductSkuService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SKU（库存单元）控制器
 * <p>
 * 微服务拆分说明（从单体 com.example.demo.Controller.SKUController 迁移，瘦身）：
 * 1. 已移除库存扣减（decreaseStock）、秒杀相关（preheatStock/getSeckillStock/seckill）接口，
 *    这些职责整体迁至 shopai-inventory 库存服务。
 * 2. SKU 查询时，通过 InventoryClient（Feign）批量补库存字段，展示实时库存。
 * 3. 身份校验由网关统一认证，本控制器改用 CurrentUser 工具类读取 X-User-* 头。
 * <p>
 * 路径：/api/sku/** 保持不变（见设计文档 5.3）。
 */
@RestController
@RequestMapping("/api/sku")
@RequiredArgsConstructor
public class SKUController {

    private final ProductSkuService skuService;
    private final InventoryClient inventoryClient;
    private static final Logger log = LoggerFactory.getLogger(SKUController.class);

    // ==================== 查询 ====================

    /**
     * 根据商品ID查询该商品的所有SKU（用于商品详情页选择颜色/尺码）
     * <p>
     * 微服务拆分说明：SKU 本身不带库存字段，此处通过 Feign 批量查询库存服务
     * 补 stock 字段；库存服务不可用时降级为空（不显示库存，见 InventoryClientFallback）。
     *
     * @param productId 商品ID
     */
    @GetMapping("/list/{productId}")
    public Result<?> getSkusByProductId(@PathVariable Long productId) {
        List<ProductSku> skus = skuService.getSkusByProductId(productId);
        fillStock(skus);
        return Result.ok("SKU列表", skus);
    }

    /**
     * 根据SKU ID查询单个SKU详情
     *
     * @param id SKU ID
     */
    @GetMapping("/{id}")
    public Result<?> getSkuById(@PathVariable Long id) {
        ProductSku sku = skuService.getSkuById(id);
        if (sku == null) {
            return Result.error("SKU不存在");
        }
        fillStock(List.of(sku));
        return Result.ok(sku);
    }

    /**
     * 根据SKU编码查询SKU（通常用于后台管理，不缓存）
     *
     * @param skuCode SKU编码，如 "T001-RED-M"
     */
    @GetMapping("/code/{skuCode}")
    public Result<?> getBySkuCode(@PathVariable String skuCode) {
        ProductSku sku = skuService.getBySkuCode(skuCode);
        if (sku == null) {
            return Result.error("SKU不存在");
        }
        fillStock(List.of(sku));
        return Result.ok(sku);
    }

    // ==================== 写操作（商户） ====================

    /**
     * 新增SKU（仅商户）
     * <p>
     * 请求体示例：{ "productId": 1, "skuCode": "T001-RED-M", "color": "红色", "size": "M", "price": 129.00, "stock": 100 }
     * 微服务拆分说明：stock 字段已不归商品服务管，新增 SKU 后库存初始化由
     * shopai-inventory 服务的 init 接口负责（或后续由订单/库存服务协作），此处不落库 stock。
     */
    @PostMapping("/add")
    public Result<?> addSku(HttpServletRequest request, @RequestBody ProductSku sku) {
        // 网关统一认证：仅商户可添加SKU
        if (!CurrentUser.isRole(request, "merchant")) {
            return Result.error("无权操作，仅商户可添加SKU");
        }
        try {
            skuService.addSku(sku);
            log.info("商户新增SKU: id={}, skuCode={}", sku.getId(), sku.getSkuCode());
            return Result.ok("SKU新增成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 更新SKU信息（仅商户，如修改价格、图片等；库存字段不再由本服务维护）
     */
    @PutMapping("/update")
    public Result<?> updateSku(HttpServletRequest request, @RequestBody ProductSku sku) {
        // 网关统一认证：仅商户可编辑SKU
        if (!CurrentUser.isRole(request, "merchant")) {
            return Result.error("无权操作，仅商户可编辑SKU");
        }
        try {
            skuService.updateSku(sku);
            log.info("商户更新SKU: id={}", sku.getId());
            return Result.ok("SKU更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除SKU（物理删除，仅商户）
     * <p>
     * 删除前应确认该SKU无未完成订单，避免数据不一致。
     */
    @DeleteMapping("/delete/{id}")
    public Result<?> deleteSku(HttpServletRequest request, @PathVariable Long id) {
        // 网关统一认证：仅商户可删除SKU
        if (!CurrentUser.isRole(request, "merchant")) {
            return Result.error("无权操作，仅商户可删除SKU");
        }
        try {
            skuService.deleteSku(id);
            log.info("商户删除SKU: id={}", id);
            return Result.ok("SKU删除成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    // ==================== 私有：Feign 批量补库存字段 ====================

    /**
     * 通过 Feign 批量查询库存服务，回填 SKU 的 stock 字段
     * <p>
     * 微服务拆分说明：库存独立后，商品服务不再持有真实库存，SKU 展示需要跨服务补字段。
     * 降级策略：库存服务不可用时返回空列表，SKU 的 stock 字段保持历史值/空值，
     * 前端据此不显示库存或显示"库存未知"（见 InventoryClientFallback）。
     *
     * @param skus 待补库存的 SKU 列表
     */
    private void fillStock(List<ProductSku> skus) {
        if (skus == null || skus.isEmpty()) {
            return;
        }
        // 收集所有 SKU ID
        List<Long> ids = skus.stream()
                .map(ProductSku::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }
        String skuIds = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        // Feign 批量查询库存
        List<StockItem> items = inventoryClient.batchGetStock(skuIds);
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<Long, Integer> stockMap = items.stream()
                .filter(it -> it.getSkuId() != null)
                .collect(Collectors.toMap(StockItem::getSkuId, StockItem::getStock, (a, b) -> b));
        // 回填 stock 字段
        for (ProductSku sku : skus) {
            Integer stock = stockMap.get(sku.getId());
            if (stock != null) {
                sku.setStock(stock);
            }
        }
    }
}
