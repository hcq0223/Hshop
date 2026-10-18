package com.shopai.product.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.product.entity.Product;
import com.shopai.product.entity.ProductSku;
import com.shopai.product.mapper.ProductMapper;
import com.shopai.product.mapper.ProductSkuMapper;
import com.shopai.product.mq.ProductEventPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SKU（最小库存单元）服务实现
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
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;ProductSkuMapper, ProductSku&gt; 实现 ProductSkuService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class ProductSkuServiceImpl extends ServiceImpl<ProductSkuMapper, ProductSku> implements ProductSkuService {

    private static final Logger log = LoggerFactory.getLogger(ProductSkuService.class);

    @Autowired
    private ProductSkuMapper skuMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    // 列表查询改造新增：SKU 变更会改动商品价格区间/图片，需同步 product 索引
    @Autowired
    private ProductEventPublisher productEventPublisher;

    // ==================== 查询方法（缓存） ====================

    /**
     * 根据商品ID获取该商品下所有SKU列表
     * 缓存Key：RedisService.CACHE_SKU_LIST + productId
     *
     * @param productId 商品ID
     * @return SKU列表
     */
    @Override
    public List<ProductSku> getSkusByProductId(Long productId) {
        String key = RedisService.CACHE_SKU_LIST + productId;
        return redisService.getOrSetTyped(
                key,
                new TypeReference<List<ProductSku>>() {},
                300L,
                () -> skuMapper.selectByProductId(productId)
        );
    }

    /**
     * 根据SKU ID获取单个SKU详情（附带关联Product）
     * 缓存Key：RedisService.CACHE_SKU + id
     *
     * @param id SKU ID
     * @return SKU实体（product 字段已填充）
     */
    @Override
    public ProductSku getSkuById(Long id) {
        String key = RedisService.CACHE_SKU + id;
        ProductSku sku = redisService.getOrSet(key, ProductSku.class, 300L,
                () -> skuMapper.selectBySkuId(id)
        );
        if (sku != null && sku.getProductId() != null) {
            Product product = productMapper.selectById(sku.getProductId());
            sku.setProduct(product);
        }
        return sku;
    }

    /**
     * 根据SKU编码查询（通常不缓存，因为查询频率低）
     *
     * @param skuCode SKU编码
     * @return SKU实体
     */
    @Override
    public ProductSku getBySkuCode(String skuCode) {
        return skuMapper.selectBySkuCode(skuCode);
    }

    // ==================== 写操作（事务 + 缓存失效） ====================

    /**
     * 新增SKU
     * <p>
     * 微服务拆分说明（列表查询改造）：新增 SKU 会改变商品的
     * {@code price_min / price_max / images}（见 {@link #syncProductFromSkus}），
     * 而这些字段正是 product 索引里的价格筛选与列表展示字段。
     * 因此除清缓存外，必须通知搜索服务重新同步该商品的索引文档，
     * 否则「按价格区间筛选商品」会基于旧价格区间，列表价格也不更新。
     *
     * @param sku SKU实体
     */
    @Override
    @Transactional
    public void addSku(ProductSku sku) {
        skuMapper.insert(sku);
        syncProductFromSkus(sku.getProductId());
        evictSkuCacheAfterCommit(sku.getId(), sku.getProductId());
        publishProductChangedAfterCommit(sku.getProductId());
    }

    /**
     * 更新SKU信息
     * 更新后同时清除所属商品的详情缓存（因为商品的价格区间可能变化）
     * <p>
     * 微服务拆分说明（列表查询改造）：SKU 改价会影响商品价格区间，
     * 故一并同步 product 索引（理由同 {@link #addSku}）。
     *
     * @param sku SKU实体（id必填）
     */
    @Override
    @Transactional
    public void updateSku(ProductSku sku) {
        ProductSku old = skuMapper.selectBySkuId(sku.getId());
        if (old == null) {
            throw new RuntimeException("SKU不存在");
        }
        skuMapper.updateById(sku);
        syncProductFromSkus(old.getProductId());
        evictSkuCacheAfterCommit(sku.getId(), old.getProductId());
        // SKU 变动会影响商品详情中的价格区间与图片集合；
        // 清商品详情缓存 + 同步索引都放到事务提交后执行
        AfterCommitExecutor.run(
                () -> redisService.deleteKey(RedisService.CACHE_PRODUCT + old.getProductId()));
        publishProductChangedAfterCommit(old.getProductId());
    }

    /**
     * 删除SKU（物理删除）
     * <p>
     * 微服务拆分说明（列表查询改造）：删除 SKU 会重算商品价格区间与图片集合，
     * 故一并同步 product 索引（理由同 {@link #addSku}）。
     *
     * @param id SKU ID
     */
    @Override
    @Transactional
    public void deleteSku(Long id) {
        ProductSku sku = skuMapper.selectBySkuId(id);
        if (sku == null) {
            throw new RuntimeException("SKU不存在");
        }
        skuMapper.deleteById(id);
        syncProductFromSkus(sku.getProductId());
        evictSkuCacheAfterCommit(id, sku.getProductId());
        AfterCommitExecutor.run(
                () -> redisService.deleteKey(RedisService.CACHE_PRODUCT + sku.getProductId()));
        publishProductChangedAfterCommit(sku.getProductId());
    }

    /**
     * 事务提交后通知搜索服务同步该商品的索引文档
     * <p>
     * 统一收口 SKU 三处写操作对 product 索引的影响；productId 为空时跳过。
     * 事务安全：放在提交后（AfterCommitExecutor），避免消费者回源读到旧的价格区间。
     */
    private void publishProductChangedAfterCommit(Long productId) {
        if (productId == null) {
            return;
        }
        Long pid = productId;
        AfterCommitExecutor.run(() -> productEventPublisher.publish("update", pid));
    }

    // ==================== 商品汇总字段同步 ====================

    /**
     * 根据商品下所有 SKU 汇总并回写 products 表的冗余字段：
     * - images：去重后所有 SKU 的 image_url 列表
     * - priceMin：所有 SKU 中的最低价
     * - priceMax：所有 SKU 中的最高价
     */
    private void syncProductFromSkus(Long productId) {
        if (productId == null) return;
        List<ProductSku> skus = skuMapper.selectByProductId(productId);

        List<String> images = skus.stream()
                .map(ProductSku::getImageUrl)
                .filter(Objects::nonNull)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        BigDecimal priceMin = skus.stream()
                .map(ProductSku::getPrice)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal priceMax = skus.stream()
                .map(ProductSku::getPrice)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        Product update = new Product();
        update.setId(productId);
        update.setImages(images);
        update.setPriceMin(priceMin);
        update.setPriceMax(priceMax);
        productMapper.updateById(update);

        log.debug("同步商品汇总字段, productId={}, images={}, priceMin={}, priceMax={}",
                productId, images.size(), priceMin, priceMax);
    }

    // ==================== 缓存失效核心逻辑（延迟双删） ====================

    /**
     * 事务提交后清除SKU相关缓存（延迟双删模式）
     * <p>
     * 清除范围：
     * - 单个SKU缓存：cache:sku:{skuId}
     * - 所属商品下的SKU列表缓存：cache:sku:list:{productId}
     * <p>
     * 延迟双删目的：防止并发读在事务提交前将旧数据写回缓存。
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor}。
     * 原实现直接调用 {@code registerSynchronization}，在**没有事务**时会抛
     * {@code IllegalStateException}（例如方法被同类自调用导致事务代理未生效）；
     * AfterCommitExecutor 在无事务时退化为立即执行，不会因此丢失缓存清理。
     *
     * @param skuId     SKU ID（可为null）
     * @param productId 商品ID（可为null）
     */
    private void evictSkuCacheAfterCommit(Long skuId, Long productId) {
        AfterCommitExecutor.run(() -> {
            // 立即删除
            evictSkuCacheNow(skuId, productId);

            // 异步延迟再删一次
            cacheDelayScheduler.schedule(() -> {
                try {
                    evictSkuCacheNow(skuId, productId);
                    log.debug("延迟双删SKU缓存成功, skuId={}, productId={}", skuId, productId);
                } catch (Exception e) {
                    log.error("延迟双删SKU缓存失败", e);
                }
            }, 300, TimeUnit.MILLISECONDS);
        });
    }

    /** 实际执行 SKU 相关缓存的删除（立即与延迟两处共用） */
    private void evictSkuCacheNow(Long skuId, Long productId) {
        if (skuId != null) {
            redisService.deleteKey(RedisService.CACHE_SKU + skuId);
        }
        if (productId != null) {
            redisService.deleteKey(RedisService.CACHE_SKU_LIST + productId);
        }
    }
}
