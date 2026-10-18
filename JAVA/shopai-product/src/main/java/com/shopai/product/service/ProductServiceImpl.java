package com.shopai.product.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.Result;
import com.shopai.common.constant.IndexTypes;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.product.entity.Merchant;
import com.shopai.product.entity.Product;
import com.shopai.product.entity.ProductSku;
import com.shopai.product.feign.MerchantInternalClient;
import com.shopai.product.feign.SearchIndexClient;
import com.shopai.product.mapper.ProductMapper;
import com.shopai.product.mapper.ProductSkuMapper;
import com.shopai.product.mq.ProductEventPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * 商品（SPU）服务实现
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
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;ProductMapper, Product&gt; 实现 ProductService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法（list/getById/save/updateById/page/lambdaQuery 等）；
 * 保留原 @Autowired 字段与方法体，现有 mapper 调用不变。
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private ProductEventPublisher productEventPublisher;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    // 微服务拆分新增：Feign 客户端（店铺名/Logo 原为 JOIN merchants，改为调商家服务补齐）
    @Autowired
    private MerchantInternalClient merchantInternalClient;

    // 列表查询改造新增：列表走 ES 索引（替代原 Redis 列表/热销榜缓存 + deleteByPrefix 全量失效）
    @Autowired
    private SearchIndexClient searchIndexClient;
    @Autowired
    private ProductIndexResultMapper productIndexResultMapper;

    // 合法状态枚举值（对应数据库 ENUM）
    private static final List<String> VALID_STATUSES = Arrays.asList("draft", "on_sale", "off_sale");

    // ==================== 查询方法（缓存） ====================

    /**
     * 获取商品详情（联查分类与 SKU；店铺信息由 Feign 调商家服务补齐）
     * 缓存Key：RedisService.CACHE_PRODUCT + id
     * <p>
     * 微服务拆分说明：原 {@code ProductMapper.selectDetailById} 用
     * {@code INNER JOIN merchants m} 取店铺名/Logo。按「独立库」处理后
     * merchants 表归属 shopai-merchant 服务，改为本方法在拿到商品后
     * 调商家服务内部接口补齐 {@code merchant / shopName / shopLogo}，
     * 对外返回结构与改造前保持一致。
     *
     * @param id 商品ID
     * @return 商品完整信息（含商户、分类、SKU）
     */
    @Override
    public Product getDetail(Long id) {
        String key = RedisService.CACHE_PRODUCT + id;
        return redisService.getOrSet(key, Product.class, 300L,
                () -> {
                    Product product = productMapper.selectDetailById(id);
                    enrichShopInfo(product);
                    return product;
                }
        );
    }

    /**
     * 根据商户ID查询商品列表（店铺页 / 商户后台上架管理）
     * <p>
     * 微服务拆分说明（列表查询改造）：原实现直接 {@code productMapper.selectByMerchantId}
     * 查库（未缓存），且全量返回。改造后走 ES 索引：
     * - 支持分页，避免店铺商品多时一次性全量返回（原实现无分页，是潜在性能问题）；
     * - 店铺内按关键词/分类过滤可直接由 ES 承担（原 /api/product/merchant/{id}/search
     *   已迁至 shopai-search，这里把「列表」也统一收敛到 ES）；
     * - 不再需要为「商户上架/改价」清商品列表缓存（原来要 deleteByPrefix 全清）。
     * <p>
     * 兼容性：为保持与原接口「返回该商户全部商品」的语义，这里用较大的 size 取数；
     * 前端如需分页可直接传 page/size。
     *
     * @param merchantId 商户ID
     */
    @Override
    public List<Product> listByMerchant(Long merchantId) {
        return listByMerchant(merchantId, null, 1, MERCHANT_LIST_MAX_SIZE);
    }

    /**
     * 按商户查询商品列表（支持分页与关键字）
     *
     * @param merchantId 商户ID
     * @param keyword    关键字（可选，匹配商品名/描述）
     * @param page       页码（从 1 开始）
     * @param size       每页条数
     */
    public List<Product> listByMerchant(Long merchantId, String keyword, int page, int size) {
        Map<String, String> filters = new HashMap<>();
        filters.put("merchantId", String.valueOf(merchantId));
        IndexListResult result = queryIndex(filters, keyword, "createdAt", "desc", page, size);
        return productIndexResultMapper.toProducts(result);
    }

    /**
     * 获取热销商品（按销量降序，取前N条）
     * <p>
     * 微服务拆分说明（列表查询改造）：原实现走 Redis 缓存 {@code cache:product:hot:{limit}}，
     * 且任意商品增删改都会清该缓存并 {@code deleteByPrefix(CACHE_PRODUCT_LIST)}。
     * 改造后直接查 ES：按 {@code salesCount desc} 排序 + {@code status=on_sale} 过滤，
     * 排序与分页由 ES 完成，天然实时且无需缓存失效。
     *
     * @param limit 数量
     * @return 热销商品列表
     */
    @Override
    public List<Product> getHotProducts(int limit) {
        Map<String, String> filters = new HashMap<>();
        filters.put("status", "on_sale");
        IndexListResult result = queryIndex(filters, null, "salesCount", "desc", 1, limit);
        return productIndexResultMapper.toProducts(result);
    }

    // ==================== 列表查询（ES 索引） ====================

    /** 店铺商品列表一次最多返回条数（兼容原接口的全量语义，同时避免无上限拉取） */
    private static final int MERCHANT_LIST_MAX_SIZE = 200;

    /**
     * 统一走搜索服务的索引列表查询
     * <p>
     * 降级策略：搜索服务不可用时返回空结果并记录错误日志。调用方据此返回空列表，
     * 而不是让商品服务的列表接口整体 500（避免搜索服务故障放大）。
     */
    private IndexListResult queryIndex(Map<String, String> filters, String keyword,
                                       String sortField, String sortOrder, int page, int size) {
        IndexListQuery query = new IndexListQuery();
        query.setIndexType(IndexTypes.PRODUCT);
        query.setPage(page);
        query.setSize(size);
        query.setKeyword(keyword);
        query.setSortField(sortField);
        query.setSortOrder(sortOrder);
        query.setFilters(filters);
        try {
            Result<IndexListResult> r = searchIndexClient.list(query);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData();
            }
            log.warn("商品列表索引查询返回异常: code={}, msg={}",
                    r == null ? null : r.getCode(), r == null ? null : r.getMessage());
        } catch (Exception e) {
            log.error("调用搜索服务查询商品列表失败, filters={}, page={}", filters, page, e);
        }
        IndexListResult empty = new IndexListResult();
        empty.setRecords(java.util.Collections.emptyList());
        empty.setTotal(0);
        empty.setPage(page);
        empty.setSize(size);
        empty.setPages(0);
        return empty;
    }

    // ==================== 跨服务：店铺信息补齐（Feign 替代 JOIN merchants） ====================

    /**
     * 为单个商品补齐店铺信息（店铺名/Logo/商户对象）
     * <p>
     * 微服务拆分说明：替代原 {@code ProductMapper.xml} 中对 merchants 表的 JOIN。
     * 降级策略：商家服务不可用时静默跳过，商品本身信息照常返回。
     */
    private void enrichShopInfo(Product product) {
        if (product == null || product.getMerchantId() == null) {
            return;
        }
        MerchantBrief brief = loadShopBriefs(java.util.Collections.singletonList(product.getMerchantId()))
                .get(product.getMerchantId());
        if (brief != null) {
            applyShop(product, brief);
        }
    }

    /**
     * 为多个商品批量补齐店铺信息（店铺名/Logo/商户对象）
     * <p>
     * 一次 Feign 调用处理全部 merchantId，避免逐条调用造成 N+1。
     */
    private void enrichShopInfoBatch(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return;
        }
        List<Long> merchantIds = products.stream()
                .map(Product::getMerchantId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (merchantIds.isEmpty()) {
            return;
        }
        Map<Long, MerchantBrief> shopMap = loadShopBriefs(merchantIds);
        for (Product p : products) {
            MerchantBrief brief = p.getMerchantId() == null ? null : shopMap.get(p.getMerchantId());
            if (brief != null) {
                applyShop(p, brief);
            }
        }
    }

    /**
     * 调用商家服务内部接口批量查询店铺简要信息
     * <p>
     * 降级策略：异常或非 200 时返回空 Map（商品服务不因商家服务故障而不可用）。
     */
    private Map<Long, MerchantBrief> loadShopBriefs(List<Long> merchantIds) {
        Map<Long, MerchantBrief> map = new HashMap<>();
        String ids = merchantIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<MerchantBrief>> r = merchantInternalClient.listBriefs(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (MerchantBrief b : r.getData()) {
                    if (b.getId() != null) {
                        map.put(b.getId(), b);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取店铺信息失败（商品将缺少店铺字段），merchantIds={}", ids, e);
        }
        return map;
    }

    /** 把店铺信息写入 Product 的冗余展示字段与 merchant 关联对象 */
    private void applyShop(Product product, MerchantBrief brief) {
        product.setShopName(brief.getShopName());
        product.setShopLogo(brief.getLogoUrl());
        // 详情接口原返回 merchant 关联对象，此处保持一致（rating/isVerified 由商家服务提供）
        Merchant merchant = new Merchant();
        merchant.setId(brief.getId());
        merchant.setShopName(brief.getShopName());
        merchant.setLogoUrl(brief.getLogoUrl());
        merchant.setRating(brief.getRating());
        merchant.setIsVerified(brief.getIsVerified());
        product.setMerchant(merchant);
    }

    // ==================== 写操作（事务 + 缓存失效） ====================

    /**
     * 新增商品
     * 默认状态为 draft
     *
     * @param product 商品实体（需包含商户ID、分类ID等）
     */
    @Override
    @Transactional
    public void addProduct(Product product) {
        if (product.getStatus() == null) {
            product.setStatus("draft");
        }
        if (!VALID_STATUSES.contains(product.getStatus())) {
            throw new IllegalArgumentException("无效的商品状态: " + product.getStatus());
        }
        productMapper.insert(product);
        evictProductCacheAfterCommit(product.getId(), product.getMerchantId());
        // 微服务拆分说明：新增商品后发 MQ 通知搜索服务同步 ES。
        // 事务安全：必须在事务提交后发（否则消费者回源看不到这条商品，
        // 会把「不存在」当成结论；若本事务回滚，消息也已发出，索引将永远脏着）。
        AfterCommitExecutor.run(
                () -> productEventPublisher.publish("create", product.getId()));
    }

    /**
     * 更新商品信息（全量更新，传入非空字段会被更新）
     *
     * @param product 商品实体（id必填）
     */
    @Override
    @Transactional
    public void updateProduct(Product product) {
        Product old = productMapper.selectById(product.getId());
        if (old == null) {
            throw new RuntimeException("商品不存在");
        }
        if (product.getStatus() != null && !VALID_STATUSES.contains(product.getStatus())) {
            throw new IllegalArgumentException("无效的商品状态: " + product.getStatus());
        }
        productMapper.updateById(product);
        evictProductCacheAfterCommit(product.getId(), old.getMerchantId());
        // 事务安全：提交后再发 MQ（同上，避免消费者读到未提交的旧值）
        AfterCommitExecutor.run(
                () -> productEventPublisher.publish("update", product.getId()));
    }

    /**
     * 单独修改商品状态（上架/下架/草稿）
     *
     * @param id     商品ID
     * @param status 新状态（draft/on_sale/off_sale）
     */
    @Override
    @Transactional
    public void updateStatus(Long id, String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("无效的商品状态: " + status);
        }
        // 上架前检查：商品必须至少有一个SKU
        if ("on_sale".equals(status)) {
            List<ProductSku> skus = productSkuMapper.selectByProductId(id);
            if (skus == null || skus.isEmpty()) {
                throw new RuntimeException("该商品没有规格（SKU），请先添加规格再上架");
            }
        }
        Product product = new Product();
        product.setId(id);
        product.setStatus(status);
        productMapper.updateById(product);
        // 清除缓存时需要知道商户ID（用于清商户缓存），需重新查询
        Product old = productMapper.selectById(id);
        evictProductCacheAfterCommit(id, old != null ? old.getMerchantId() : null);
        // 微服务拆分说明：状态变更（上/下架）需同步 ES 可搜状态。
        // 事务安全：提交后再发，否则搜索侧可能把「已下架」写成「在售」。
        AfterCommitExecutor.run(
                () -> productEventPublisher.publish("update", id));
    }

    /**
     * 删除商品（物理删除，注意：若使用逻辑删除请改为更新 deleted 字段）
     *
     * @param id 商品ID
     */
    @Override
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        productMapper.deleteById(id);
        evictProductCacheAfterCommit(id, product.getMerchantId());
        // 微服务拆分说明：删除商品后发 MQ 通知搜索服务从 ES 移除。
        // 事务安全：提交后再发，避免「事务回滚但索引已删」导致商品在列表里凭空消失。
        AfterCommitExecutor.run(
                () -> productEventPublisher.publish("delete", id));
    }

    // ==================== 跨服务内部接口：累加销量 ====================

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
    @Override
    @Transactional
    public void incrementSalesCount(Long productId, int delta) {
        productMapper.incrementSalesCount(productId, delta);
        // 列表查询改造说明：原此处还要 deleteByPrefix(CACHE_PRODUCT_LIST) 清空所有商品列表
        // 与热销榜缓存；现在列表/热销榜都查 ES，只需通知搜索服务更新该商品的索引文档
        // （销量是热销榜的排序字段，必须同步，否则热销榜排序不更新）。
        //
        // 事务安全：缓存清理与 MQ 都放到提交后执行。
        // 原实现是在事务内直接 deleteKey + publish：
        //   - 缓存先删了，若事务回滚则缓存被无谓清空（读回源仍拿到旧值，多打一次库）；
        //   - 消息先发了，若事务回滚则索引里的销量被改成一个并未发生的值。
        AfterCommitExecutor.run(
                () -> redisService.deleteKey(RedisService.CACHE_PRODUCT + productId),
                () -> productEventPublisher.publish("update", productId));
    }

    // ==================== 缓存失效核心逻辑（延迟双删） ====================

    /**
     * 事务提交后清除商品相关缓存（延迟双删模式）
     * <p>
     * 清除范围：
     * - 单个商品详情：cache:product:{productId}
     * - 仪表盘缓存
     * - 所属商户的缓存（通过 clearShopCache 清除商户相关的缓存）
     * <p>
     * 延迟双删目的：防止并发读在事务提交前将旧数据写回缓存。
     * <p>
     * 列表查询改造说明：原实现还包含两条 {@code deleteByPrefix(CACHE_PRODUCT_LIST)}
     * （立即 + 延迟），用于清空**所有页码、所有筛选组合**的商品列表缓存 ——
     * 这正是本次改造要消除的重失效。商品列表/热销榜已改查 ES 索引
     * （见 {@link #listByMerchant}、{@link #getHotProducts}），
     * 因此这里不再清理列表缓存；索引的实时性由 MQ 事件保证
     * （见 {@link com.shopai.product.mq.ProductEventPublisher}）。
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor} 统一「提交后执行」语义，
     * 避免各服务各写一遍 {@code registerSynchronization} 模板代码。
     *
     * @param productId  商品ID（可为null）
     * @param merchantId 商户ID（可为null）
     */
    private void evictProductCacheAfterCommit(Long productId, Long merchantId) {
        AfterCommitExecutor.run(() -> {
            evictProductCacheNow(productId, merchantId);

            // 异步延迟再删一次（应对「提交后仍有并发读把旧值写回」的窄窗口）
            cacheDelayScheduler.schedule(() -> {
                try {
                    evictProductCacheNow(productId, merchantId);
                    log.debug("延迟双删商品缓存成功, productId={}", productId);
                } catch (Exception e) {
                    log.error("延迟双删商品缓存失败", e);
                }
            }, 300, TimeUnit.MILLISECONDS);
        });
    }

    /** 实际执行商品相关缓存的删除（立即与延迟两处共用） */
    private void evictProductCacheNow(Long productId, Long merchantId) {
        if (productId != null) {
            redisService.deleteKey(RedisService.CACHE_PRODUCT + productId);
        }
        redisService.deleteKey(RedisService.CACHE_DASHBOARD);
        if (merchantId != null) {
            redisService.clearShopCache(merchantId);
        }
    }
}
