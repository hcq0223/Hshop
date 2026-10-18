package com.shopai.product.service;

import com.shopai.product.entity.ProductSku;

import java.util.List;

/**
 * SKU（最小库存单元）服务接口
 * <p>
 * 缓存策略：
 * - 单个SKU：cache:sku:{id} （TTL 300秒）
 * - 商品下的SKU列表：cache:sku:list:{productId} （TTL 300秒）
 * <p>
 * 失效策略（写操作后）：
 * 1. 事务提交后立即删除该SKU单个缓存、所属商品下的SKU列表缓存、以及所属商品详情缓存
 * 2. 异步延迟300ms再次删除上述缓存（防止并发读脏数据）
 * <p>
 * 微服务拆分说明（从单体 com.example.demo.service.ProductSkuService 迁移，瘦身）：
 * 1. 已移除全部库存相关方法：preheatStock / getSeckillStock / seckill /
 *    asyncDeductDatabaseAndCompensate / decreaseStockSync / decreaseStock / restoreStockBySkuId
 *    （库存扣减/秒杀/预热整体迁至 shopai-inventory 库存服务）。
 * 2. 本服务仅保留「SKU 规格信息的增删改查」+「商品汇总字段同步」职责，
 *    SKU 查询时的库存展示由 SKUController 通过 Feign 批量补字段。
 * <p>
 * 分层改造（2026-10-05）：本接口 + ProductSkuServiceImpl（extends ServiceImpl&lt;ProductSkuMapper, ProductSku&gt;）标准分层。
 */
public interface ProductSkuService {

    /**
     * 根据商品ID获取该商品下所有SKU列表
     * 缓存Key：RedisService.CACHE_SKU_LIST + productId
     *
     * @param productId 商品ID
     * @return SKU列表
     */
    List<ProductSku> getSkusByProductId(Long productId);

    /**
     * 根据SKU ID获取单个SKU详情（附带关联Product）
     * 缓存Key：RedisService.CACHE_SKU + id
     *
     * @param id SKU ID
     * @return SKU实体（product 字段已填充）
     */
    ProductSku getSkuById(Long id);

    /**
     * 根据SKU编码查询（通常不缓存，因为查询频率低）
     *
     * @param skuCode SKU编码
     * @return SKU实体
     */
    ProductSku getBySkuCode(String skuCode);

    /**
     * 新增SKU
     *
     * @param sku SKU实体
     */
    void addSku(ProductSku sku);

    /**
     * 更新SKU信息
     * 更新后同时清除所属商品的详情缓存（因为商品的价格区间可能变化）
     *
     * @param sku SKU实体（id必填）
     */
    void updateSku(ProductSku sku);

    /**
     * 删除SKU（物理删除）
     *
     * @param id SKU ID
     */
    void deleteSku(Long id);
}
