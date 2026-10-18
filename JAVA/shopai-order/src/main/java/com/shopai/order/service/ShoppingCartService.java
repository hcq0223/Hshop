package com.shopai.order.service;

import com.shopai.order.entity.ShoppingCart;

import java.util.List;

/**
 * 购物车服务接口
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
 * 分层改造（2026-10-05）：本接口 + ShoppingCartServiceImpl（extends ServiceImpl&lt;ShoppingCartMapper, ShoppingCart&gt;）标准分层。
 */
public interface ShoppingCartService {

    /**
     * 获取客户购物车列表（联查SKU和商品信息）
     * 缓存Key：CACHE_CART_LIST + customerId
     * TTL：180秒（购物车数据变动频繁）
     *
     * @param customerId 客户ID
     * @return 购物车列表（含SKU和商品详情）
     */
    List<ShoppingCart> getCartByCustomerId(Long customerId);

    /**
     * 添加商品到购物车（如已存在则累加数量）
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @param quantity   数量（正数）
     */
    void addToCart(Long customerId, Long skuId, Integer quantity);

    /**
     * 更新购物车中某商品的数量
     *
     * @param cartId   购物车条目ID
     * @param quantity 新数量（必须大于0）
     */
    void updateQuantity(Long cartId, Integer quantity);

    /**
     * 判断某商品是否已在购物车中
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @return true=已加入，false=未加入
     */
    boolean existsInCart(Long customerId, Long skuId);

    /**
     * 获取购物车中某商品的具体条目（含ID、数量、选中状态等）
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @return 购物车条目，不存在则返回null
     */
    ShoppingCart getCartItemBySku(Long customerId, Long skuId);

    /**
     * 根据客户ID和SKU ID更新数量（如果不存在则新增）
     * <p>
     * 场景：前端在商品详情页直接调整数量，只有 customerId 和 skuId，没有 cartId
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @param quantity   数量（必须大于0）
     */
    void updateQuantityBySku(Long customerId, Long skuId, Integer quantity);

    /**
     * 更新购物车条目选中状态（单个）
     *
     * @param cartId   购物车条目ID
     * @param selected 1选中，0取消选中
     */
    void updateSelected(Long cartId, Integer selected);

    /**
     * 批量更新购物车条目选中状态（全选/取消全选）
     *
     * @param customerId 客户ID
     * @param ids        购物车条目ID列表（为空则忽略）
     * @param selected   1选中，0取消选中
     */
    void updateSelectedBatch(Long customerId, List<Long> ids, Integer selected);

    /**
     * 删除购物车中的某个商品（根据客户ID和SKU ID）
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     */
    void deleteCartItem(Long customerId, Long skuId);

    /**
     * 批量删除购物车条目（根据购物车条目ID列表）
     *
     * @param customerId 客户ID
     * @param ids        购物车条目ID列表
     */
    void deleteCartItems(Long customerId, List<Long> ids);

    /**
     * 清空客户购物车
     *
     * @param customerId 客户ID
     */
    void clearCart(Long customerId);
}
