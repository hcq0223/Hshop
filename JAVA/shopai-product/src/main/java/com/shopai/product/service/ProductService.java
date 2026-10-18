package com.shopai.product.service;

import com.shopai.product.entity.Product;

import java.util.List;

/**
 * 商品（SPU）服务接口
 * <p>
 * 缓存策略：
 * - 单个商品详情：cache:product:{id} （TTL 300秒）
 * - 商品列表（分页/条件）：cache:product:list:{hash} （TTL 120秒）
 * - 热销商品：cache:product:hot:{limit} （TTL 180秒）
 * <p>
 * 失效策略（写操作后）：
 * 1. 事务提交后立即删除该商品详情、所有商品列表缓存、仪表盘缓存、商户缓存
 * 2. 异步延迟300ms再次删除（防止并发读脏数据）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.ProductService 迁移至 shopai-product 服务。
 * 主要改造点：
 * 1. 已移除 searchProducts（前台 LIKE 全局搜索 + 店铺内搜索），整体迁至 shopai-search 走 ES。
 * 2. 商品增删改后通过 ProductEventPublisher 发 MQ product.changed。
 * <p>
 * 分层改造（2026-10-05）：本接口 + ProductServiceImpl（extends ServiceImpl&lt;ProductMapper, Product&gt;）标准分层，
 * 实现类可直接使用 MyBatis-Plus 的 IService 便捷方法（list/getById/save/updateById/page/lambdaQuery 等）。
 */
public interface ProductService {

    /**
     * 获取商品详情（联查商户和分类信息）
     * 缓存Key：RedisService.CACHE_PRODUCT + id
     *
     * @param id 商品ID
     * @return 商品完整信息（含商户、分类）
     */
    Product getDetail(Long id);

    /**
     * 根据商户ID查询商品列表（常用于商户店铺页展示，不缓存，因为数据变更频繁且查询条件多样）
     * <p>
     * 微服务拆分说明：店铺页内按关键词/分类/价格的过滤搜索（原 /api/product/merchant/{id}/search）
     * 已迁至 shopai-search 走 ES；本方法仅提供「店铺全部商品列表」能力。
     *
     * @param merchantId 商户ID
     * @return 商品列表
     */
    List<Product> listByMerchant(Long merchantId);

    /**
     * 获取热销商品（按销量降序，取前N条）
     * 缓存Key：cache:product:hot:{limit}
     *
     * @param limit 数量
     * @return 热销商品列表
     */
    List<Product> getHotProducts(int limit);

    /**
     * 新增商品
     * 默认状态为 draft
     *
     * @param product 商品实体（需包含商户ID、分类ID等）
     */
    void addProduct(Product product);

    /**
     * 更新商品信息（全量更新，传入非空字段会被更新）
     *
     * @param product 商品实体（id必填）
     */
    void updateProduct(Product product);

    /**
     * 单独修改商品状态（上架/下架/草稿）
     *
     * @param id     商品ID
     * @param status 新状态（draft/on_sale/off_sale）
     */
    void updateStatus(Long id, String status);

    /**
     * 删除商品（物理删除，注意：若使用逻辑删除请改为更新 deleted 字段）
     *
     * @param id 商品ID
     */
    void deleteProduct(Long id);

    /**
     * 累加商品销量（供订单服务通过内部 Feign 接口调用）
     * <p>
     * 微服务拆分说明：原单体中 incrementSalesCount 由 OrderService 下单时直接调用
     * ProductMapper；拆分后 products 表归属商品服务，订单服务通过
     * POST /api/product/internal/sales 内部接口（Feign）调用本方法。
     * 后续若需纳入 Seata 全局事务，本方法所在服务需引入 seata-starter（见风险登记）。
     *
     * @param productId 商品ID
     * @param delta     增量
     */
    void incrementSalesCount(Long productId, int delta);
}
