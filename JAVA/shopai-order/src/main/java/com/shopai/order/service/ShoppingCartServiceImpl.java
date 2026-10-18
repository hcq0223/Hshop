package com.shopai.order.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.order.entity.ProductRef;
import com.shopai.order.entity.ProductSkuRef;
import com.shopai.order.entity.ShoppingCart;
import com.shopai.order.feign.ProductInternalClient;
import com.shopai.order.mapper.ShoppingCartMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 购物车服务实现
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.ShoppingCartService 迁移至 shopai-order 服务。
 * <p>
 * 缓存策略：
 * - 购物车列表缓存：cache:cart:list:{customerId} （TTL 180秒）
 * <p>
 * 失效策略（写操作后）：
 * 1. 事务提交后立即删除该客户购物车列表缓存
 * 2. 异步延迟300ms再次删除（防止并发读脏数据）
 * <p>
 * 微服务拆分说明（独立库）：购物车列表原先在 {@code ShoppingCartMapper.xml} 中
 * {@code JOIN product_skus / products} 取 SKU 规格与商品名称/图片。按「独立库」处理后
 * 这两张表归属 shopai-product 服务，改为查询后由本服务通过 Feign 调商品服务
 * 内部接口批量补齐（见 {@link #enrichCartInfo}）。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;ShoppingCartMapper, ShoppingCart&gt; 实现 ShoppingCartService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class ShoppingCartServiceImpl extends ServiceImpl<ShoppingCartMapper, ShoppingCart> implements ShoppingCartService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingCartService.class);


    @Autowired
    private ShoppingCartMapper cartMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    // 微服务拆分新增：Feign 客户端（SKU/商品信息原为 JOIN product_skus/products）
    @Autowired
    private ProductInternalClient productInternalClient;

    // ==================== 查询（缓存） ====================

    /**
     * 获取客户购物车列表（SKU 和商品信息由 Feign 调商品服务补齐）
     * 缓存Key：CACHE_CART_LIST + customerId
     * TTL：180秒（购物车数据变动频繁）
     *
     * @param customerId 客户ID
     * @return 购物车列表（含SKU和商品详情）
     */
    @Override
    public List<ShoppingCart> getCartByCustomerId(Long customerId) {
        String key = RedisService.CACHE_CART_LIST + customerId;
        List<ShoppingCart> list = redisService.getOrSetTyped(
                key,
                new TypeReference<List<ShoppingCart>>() {},
                180L,
                () -> cartMapper.selectByCustomerId(customerId)
        );
        // 微服务拆分说明：原 SQL JOIN product_skus/products，独立库后改为 Feign 批量补齐
        enrichCartInfo(list);
        return list;
    }

    // ==================== 跨服务：SKU/商品信息补齐（Feign 替代 JOIN） ====================

    /**
     * 为购物车列表批量补齐 SKU 与商品信息
     * <p>
     * 微服务拆分说明：替代原 {@code ShoppingCartMapper.xml} 中对
     * {@code product_skus} / {@code products} 的 INNER JOIN。
     * 先按 skuId 逐个取 SKU（商品服务内部接口），再按 productId 去重批量取商品，
     * 避免同一商品重复调用。
     * <p>
     * 降级策略：单个 SKU/商品取不到时跳过该字段（不影响购物车其余条目展示）。
     */
    private void enrichCartInfo(List<ShoppingCart> carts) {
        if (carts == null || carts.isEmpty()) {
            return;
        }

        // 1. 逐个取 SKU（SKU 数量等于购物车条目数，商品服务内部接口按 id 查询）
        Map<Long, ProductSkuInfo> skuMap = new HashMap<>();
        for (ShoppingCart cart : carts) {
            if (cart.getSkuId() == null || skuMap.containsKey(cart.getSkuId())) {
                continue;
            }
            ProductSkuInfo sku = loadSku(cart.getSkuId());
            if (sku != null) {
                skuMap.put(cart.getSkuId(), sku);
            }
        }

        // 2. 按 productId 去重后批量取商品（一次 Feign 调用）
        String productIds = skuMap.values().stream()
                .map(ProductSkuInfo::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        Map<Long, ProductInfo> productMap = loadProducts(productIds);

        // 3. 回填到购物车条目
        for (ShoppingCart cart : carts) {
            ProductSkuInfo sku = cart.getSkuId() == null ? null : skuMap.get(cart.getSkuId());
            if (sku == null) {
                continue;
            }
            cart.setSku(toSkuRef(sku));
            ProductInfo product = sku.getProductId() == null ? null : productMap.get(sku.getProductId());
            if (product != null) {
                cart.setProduct(toProductRef(product));
            }
        }
    }

    /** 调商品服务按 SKU ID 查询（降级返回 null） */
    private ProductSkuInfo loadSku(Long skuId) {
        try {
            Result<ProductSkuInfo> r = productInternalClient.getSku(skuId);
            if (r != null && r.getCode() != null && r.getCode() == 200) {
                return r.getData();
            }
        } catch (Exception e) {
            log.error("调用商品服务查询 SKU 失败, skuId={}", skuId, e);
        }
        return null;
    }

    /** 调商品服务按商品ID批量查询（降级返回空 Map） */
    private Map<Long, ProductInfo> loadProducts(String ids) {
        Map<Long, ProductInfo> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<ProductInfo>> r = productInternalClient.listProducts(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (ProductInfo p : r.getData()) {
                    if (p.getId() != null) {
                        map.put(p.getId(), p);
                    }
                }
            }
        } catch (Exception e) {
            log.error("调用商品服务批量查询商品失败, ids={}", ids, e);
        }
        return map;
    }

    /** ProductSkuInfo（跨服务 DTO）→ 购物车展示用的 ProductSkuRef */
    private ProductSkuRef toSkuRef(ProductSkuInfo info) {
        ProductSkuRef ref = new ProductSkuRef();
        ref.setId(info.getId());
        ref.setProductId(info.getProductId());
        ref.setSkuCode(info.getSkuCode());
        ref.setColor(info.getColor());
        ref.setSize(info.getSize());
        ref.setPrice(info.getPrice());
        ref.setImageUrl(info.getImageUrl());
        ref.setWeight(info.getWeight());
        // 库存已由 shopai-inventory 独立管理，购物车展示不再从商品服务取 stock
        return ref;
    }

    /** ProductInfo（跨服务 DTO）→ 购物车展示用的 ProductRef */
    private ProductRef toProductRef(ProductInfo info) {
        ProductRef ref = new ProductRef();
        ref.setId(info.getId());
        ref.setMerchantId(info.getMerchantId());
        ref.setName(info.getName());
        ref.setMainImage(info.getMainImage());
        ref.setPriceMin(info.getPriceMin());
        ref.setPriceMax(info.getPriceMax());
        ref.setStatus(info.getStatus());
        return ref;
    }

    // ==================== 写操作（事务 + 延迟双删） ====================

    /**
     * 添加商品到购物车（如已存在则累加数量）
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @param quantity   数量（正数）
     */
    @Override
    @Transactional
    public void addToCart(Long customerId, Long skuId, Integer quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于0");
        }
        cartMapper.insertOrAddQuantity(customerId, skuId, quantity);
        evictCartCacheAfterCommit(customerId);
    }

    /**
     * 更新购物车中某商品的数量
     *
     * @param cartId   购物车条目ID
     * @param quantity 新数量（必须大于0）
     */
    @Override
    @Transactional
    public void updateQuantity(Long cartId, Integer quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于0");
        }
        ShoppingCart cart = cartMapper.selectById(cartId);
        if (cart == null) {
            throw new RuntimeException("购物车条目不存在");
        }
        cartMapper.updateQuantity(cartId, quantity);
        evictCartCacheAfterCommit(cart.getCustomerId());
    }


    /**
     * 判断某商品是否已在购物车中
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @return true=已加入，false=未加入
     */
    @Override
    public boolean existsInCart(Long customerId, Long skuId) {
        ShoppingCart cart = cartMapper.selectByCustomerIdAndSkuId(customerId, skuId);
        return cart != null;
    }

    /**
     * 获取购物车中某商品的具体条目（含ID、数量、选中状态等）
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @return 购物车条目，不存在则返回null
     */
    @Override
    public ShoppingCart getCartItemBySku(Long customerId, Long skuId) {
        return cartMapper.selectByCustomerIdAndSkuId(customerId, skuId);
    }

    /**
     * 根据客户ID和SKU ID更新数量（如果不存在则新增）
     * <p>
     * 场景：前端在商品详情页直接调整数量，只有 customerId 和 skuId，没有 cartId
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @param quantity   数量（必须大于0）
     */
    @Override
    @Transactional
    public void updateQuantityBySku(Long customerId, Long skuId, Integer quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于0");
        }

        // 先查是否存在
        ShoppingCart existing = cartMapper.selectByCustomerIdAndSkuId(customerId, skuId);
        if (existing != null) {
            // 存在：更新数量
            cartMapper.updateQuantity(existing.getId(), quantity);
        } else {
            // 不存在：新增（直接用插入方法）
            cartMapper.insertOrAddQuantity(customerId, skuId, quantity);
        }
        // 清除缓存
        evictCartCacheAfterCommit(customerId);
    }

    /**
     * 更新购物车条目选中状态（单个）
     *
     * @param cartId   购物车条目ID
     * @param selected 1选中，0取消选中
     */
    @Override
    @Transactional
    public void updateSelected(Long cartId, Integer selected) {
        if (selected != 0 && selected != 1) {
            throw new IllegalArgumentException("选中状态只能为0或1");
        }
        ShoppingCart cart = cartMapper.selectById(cartId);
        if (cart == null) {
            throw new RuntimeException("购物车条目不存在");
        }
        cartMapper.updateSelected(cartId, selected);
        evictCartCacheAfterCommit(cart.getCustomerId());
    }

    /**
     * 批量更新购物车条目选中状态（全选/取消全选）
     *
     * @param customerId 客户ID
     * @param ids        购物车条目ID列表（为空则忽略）
     * @param selected   1选中，0取消选中
     */
    @Override
    @Transactional
    public void updateSelectedBatch(Long customerId, List<Long> ids, Integer selected) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        cartMapper.updateSelectedBatch(ids, selected);
        evictCartCacheAfterCommit(customerId);
    }

    /**
     * 删除购物车中的某个商品（根据客户ID和SKU ID）
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     */
    @Override
    @Transactional
    public void deleteCartItem(Long customerId, Long skuId) {
        cartMapper.deleteByCustomerIdAndSkuId(customerId, skuId);
        evictCartCacheAfterCommit(customerId);
    }

    /**
     * 批量删除购物车条目（根据购物车条目ID列表）
     *
     * @param customerId 客户ID
     * @param ids        购物车条目ID列表
     */
    @Override
    @Transactional
    public void deleteCartItems(Long customerId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 建议先校验这些id是否属于该客户，此处简化处理
        cartMapper.deleteByIds(ids);
        evictCartCacheAfterCommit(customerId);
    }

    /**
     * 清空客户购物车
     *
     * @param customerId 客户ID
     */
    @Override
    @Transactional
    public void clearCart(Long customerId) {
        cartMapper.deleteByCustomerId(customerId);
        evictCartCacheAfterCommit(customerId);
    }

    // ==================== 缓存失效核心逻辑（延迟双删） ====================

    /**
     * 事务提交后清除购物车列表缓存（延迟双删模式）
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor}。
     * 原实现直接调用 {@code registerSynchronization}，在**没有事务**时会抛
     * {@code IllegalStateException}。购物车服务里 {@code getCartItemBySku} 等
     * 非事务方法也会触发缓存清理，一旦走到这里就会直接失败。
     * AfterCommitExecutor 在无事务时退化为立即执行，既修掉这个隐患，
     * 也保证「清理缓存」这件事不会因为调用方式不同而静默丢失。
     *
     * @param customerId 客户ID
     */
    private void evictCartCacheAfterCommit(Long customerId) {
        AfterCommitExecutor.run(() -> {
            String key = RedisService.CACHE_CART_LIST + customerId;
            // 1. 立即删除
            redisService.deleteKey(key);

            // 2. 异步延迟再删一次
            cacheDelayScheduler.schedule(() -> {
                try {
                    redisService.deleteKey(key);
                    log.debug("延迟双删购物车缓存成功, customerId={}", customerId);
                } catch (Exception e) {
                    log.error("延迟双删购物车缓存失败, customerId={}", customerId, e);
                }
            }, 300, TimeUnit.MILLISECONDS);
        });
    }
}
