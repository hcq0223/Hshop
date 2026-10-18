package com.shopai.product.controller;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.ProductIndexItem;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import com.shopai.product.entity.Product;
import com.shopai.product.entity.ProductSku;
import com.shopai.product.mapper.ProductMapper;
import com.shopai.product.mapper.ProductSkuMapper;
import com.shopai.product.service.ProductIndexService;
import com.shopai.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品服务内部接口（供其他服务通过 Feign 调用，不对网关暴露）
 * <p>
 * 微服务拆分说明：{@code products} / {@code product_skus} 表归属 shopai-product 服务。
 * 按「独立库」处理跨服务访问后，原先其他服务对这两张表的直查全部改为调用本控制器：
 * - shopai-order：下单按 skuId 查 SKU（价格/规格/图片）、按 productId 查商品
 *   （名称/主图/所属商家）→ 原 ProductSkuRefMapper / ProductRefMapper 跨库直查；
 *   下单成功后累加销量 → 原直调 ProductMapper.incrementSalesCount；
 * - shopai-search：索引全量重建 / MQ 增量同步的源数据
 *   → 原 ProductRefMapper.selectAllForIndex / selectOneForIndex 跨库直查并 JOIN merchants/categories；
 * - shopai-user：管理员仪表盘的商品总数 → 原 DashboardMapper.countProducts 跨库统计；
 * - shopai-merchant：评价列表/详情的商品名 → 原 ReviewMapper.xml 的 LEFT JOIN products。
 * <p>
 * 安全说明：{@code /api/product/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@RestController
@RequestMapping(InternalApiPaths.PRODUCT_INTERNAL)
@RequiredArgsConstructor
public class ProductInternalController {

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductService productService;
    private final ProductIndexService productIndexService;

    // ==================== 计数 ====================

    /**
     * 统计商品总数（用户服务仪表盘）
     * <p>
     * 原调用方：用户服务 DashboardMapper.countProducts（跨库统计 products）。
     */
    @GetMapping("/product-count")
    public Result<Long> countProducts() {
        return Result.ok(productMapper.countAll());
    }

    // ==================== 商品 ====================

    /**
     * 按商品ID查询商品信息（名称/主图/所属商家/价格区间/状态）
     * <p>
     * 原调用方：订单服务 ProductRefMapper.selectProductById（跨库直查 products）。
     *
     * @param id 商品ID
     * @return 商品信息；不存在时 data 为 null
     */
    @GetMapping("/product/{id}")
    public Result<ProductInfo> getProduct(@PathVariable("id") Long id) {
        return Result.ok(toProductInfo(productMapper.selectById(id)));
    }

    /**
     * 按商品ID批量查询商品信息
     * <p>
     * 原调用方：需要一次补齐多个商品的场景（下单按 SKU 分组、评价列表补商品名）。
     *
     * @param ids 逗号分隔的商品ID列表，如 "1,2,3"
     * @return 商品信息列表（不存在的ID自动跳过）
     */
    @GetMapping("/products")
    public Result<List<ProductInfo>> listProducts(@RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Product> products = productMapper.selectBatchIds(idList);
        List<ProductInfo> result = new ArrayList<>(products.size());
        for (Product p : products) {
            result.add(toProductInfo(p));
        }
        return Result.ok(result);
    }

    /**
     * 按商品ID批量查询商品简要信息（仅 id / name / mainImage）
     * <p>
     * 原调用方：商家服务 ReviewMapper.xml 的 {@code LEFT JOIN products p}
     * 只为取 {@code p.name} 作为评价的商品名。
     *
     * @param ids 逗号分隔的商品ID列表
     * @return 商品简要信息列表
     */
    @GetMapping("/product-briefs")
    public Result<List<com.shopai.common.dto.internal.product.ProductBrief>> listProductBriefs(
            @RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Product> products = productMapper.selectBatchIds(idList);
        List<com.shopai.common.dto.internal.product.ProductBrief> result = new ArrayList<>(products.size());
        for (Product p : products) {
            com.shopai.common.dto.internal.product.ProductBrief brief =
                    new com.shopai.common.dto.internal.product.ProductBrief();
            brief.setId(p.getId() == null ? null : String.valueOf(p.getId()));
            brief.setName(p.getName());
            brief.setMainImage(p.getMainImage());
            result.add(brief);
        }
        return Result.ok(result);
    }

    // ==================== SKU ====================

    /**
     * 按 SKU ID 查询 SKU 信息（价格/规格/图片/所属商品）
     * <p>
     * 原调用方：订单服务 ProductSkuRefMapper.selectSkuById（跨库直查 product_skus）。
     * <p>
     * 注意：本接口不返回库存——库存已由 shopai-inventory 的独立 inventory 表接管。
     *
     * @param id SKU ID
     * @return SKU 信息；不存在时 data 为 null
     */
    @GetMapping("/sku/{id}")
    public Result<ProductSkuInfo> getSku(@PathVariable("id") Long id) {
        return Result.ok(toSkuInfo(productSkuMapper.selectBySkuId(id)));
    }

    /**
     * 按商品ID查询该商品下所有 SKU
     * <p>
     * 原调用方：需要按商品批量取规格的场景（如商品详情页由其它服务组装）。
     *
     * @param productId 商品ID
     * @return SKU 信息列表
     */
    @GetMapping("/sku/by-product/{productId}")
    public Result<List<ProductSkuInfo>> listSkusByProduct(@PathVariable("productId") Long productId) {
        List<ProductSku> skus = productSkuMapper.selectByProductId(productId);
        List<ProductSkuInfo> result = new ArrayList<>(skus.size());
        for (ProductSku sku : skus) {
            result.add(toSkuInfo(sku));
        }
        return Result.ok(result);
    }

    // ==================== 销量 ====================

    /**
     * 累加商品销量（订单服务下单成功后调用）
     * <p>
     * 原调用方：订单服务直调 ProductMapper.incrementSalesCount（跨库写 products）。
     *
     * @param productId 商品ID
     * @param delta     增量（通常为购买数量）
     */
    @PostMapping("/sales")
    public Result<?> incrementSalesCount(@RequestParam("productId") Long productId,
                                        @RequestParam(value = "delta", defaultValue = "1") int delta) {
        productService.incrementSalesCount(productId, delta);
        return Result.ok("销量累加成功");
    }

    // ==================== 搜索索引源数据 ====================

    /**
     * 全量商品索引条目（搜索服务全量重建 product 索引用）
     * <p>
     * 原调用方：搜索服务 ProductRefMapper.selectAllForIndex
     * （跨库直查 products 并 JOIN merchants/categories）。
     * 拆分后由本服务在本地联查分类、Feign 调商家服务补店铺名/Logo 后返回。
     */
    @GetMapping("/index/products")
    public Result<List<ProductIndexItem>> indexAllProducts() {
        return Result.ok(productIndexService.buildAll());
    }

    /**
     * 按商品ID批量获取索引条目（搜索服务 MQ 增量同步用）
     * <p>
     * 原调用方：搜索服务 ProductRefMapper.selectOneForIndex。
     *
     * @param ids 逗号分隔的商品ID列表；传空表示「查不到」（用于触发索引删除）
     */
    @GetMapping("/index/product")
    public Result<List<ProductIndexItem>> indexProducts(@RequestParam(value = "ids", required = false) String ids) {
        return Result.ok(productIndexService.buildByIds(parseIds(ids)));
    }

    // ==================== 辅助方法 ====================

    /** Product 实体 → 跨服务 ProductInfo DTO */
    private ProductInfo toProductInfo(Product p) {
        if (p == null) {
            return null;
        }
        ProductInfo info = new ProductInfo();
        info.setId(p.getId());
        info.setMerchantId(p.getMerchantId());
        info.setName(p.getName());
        info.setMainImage(p.getMainImage());
        info.setPriceMin(p.getPriceMin());
        info.setPriceMax(p.getPriceMax());
        info.setStatus(p.getStatus());
        return info;
    }

    /** ProductSku 实体 → 跨服务 ProductSkuInfo DTO（不含库存） */
    private ProductSkuInfo toSkuInfo(ProductSku sku) {
        if (sku == null) {
            return null;
        }
        ProductSkuInfo info = new ProductSkuInfo();
        info.setId(sku.getId());
        info.setProductId(sku.getProductId());
        info.setSkuCode(sku.getSkuCode());
        info.setColor(sku.getColor());
        info.setSize(sku.getSize());
        info.setPrice(sku.getPrice());
        info.setImageUrl(sku.getImageUrl());
        info.setWeight(sku.getWeight());
        return info;
    }

    /** 解析逗号分隔的ID串，忽略非法片段 */
    private static List<Long> parseIds(String ids) {
        List<Long> result = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            return result;
        }
        for (String part : ids.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.valueOf(s));
            } catch (NumberFormatException ignored) {
                // 忽略非法ID片段
            }
        }
        return result;
    }
}
