package com.shopai.merchant.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.CustomerInfo;
import com.shopai.common.dto.internal.OrderInfo;
import com.shopai.common.dto.internal.product.ProductBrief;
import com.shopai.common.mq.IndexChangedEvent;
import com.shopai.common.mq.IndexEventPublisher;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.merchant.entity.CustomerRef;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.entity.Review;
import com.shopai.merchant.feign.CustomerInternalClient;
import com.shopai.merchant.feign.OrderInternalClient;
import com.shopai.merchant.feign.ProductInternalClient;
import com.shopai.merchant.mapper.MerchantMapper;
import com.shopai.merchant.mapper.ReviewMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 商品评价服务实现
 * 缓存策略：商品评价列表（TTL 300s）、客户评价列表（TTL 180s）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.ReviewService 迁移至 shopai-merchant 服务。
 * 主要改造点（按「独立库」处理，跨服务访问一律走 Feign）：
 * 1. orders 表归属 shopai-order：原 OrderRefMapper 跨库直查/直写
 *    → OrderInternalClient（getOrder / markReviewed）。
 * 2. customers 表归属 shopai-user：原 ReviewMapper.xml 的 {@code LEFT JOIN customers}
 *    → CustomerInternalClient.listCustomers 批量补齐评价人昵称/头像。
 * 3. products 表归属 shopai-product：原 {@code LEFT JOIN products} 取商品名
 *    → ProductInternalClient.listProductBriefs 批量补齐。
 * 4. merchants 表归属本服务：商家名直接由本地 MerchantMapper 批量补齐。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;ReviewMapper, Review&gt; 实现 ReviewService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class ReviewServiceImpl extends ServiceImpl<ReviewMapper, Review> implements ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private MerchantService merchantService;
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;
    // 列表查询改造新增：评价列表走 ES 索引（替代原 Redis 评价列表缓存）
    @Autowired
    private MerchantListIndexService merchantListIndexService;
    // 列表查询改造新增：评价增删后发 MQ，搜索服务同步 review 索引
    @Autowired
    private IndexEventPublisher indexEventPublisher;
    // 微服务拆分新增：Feign 客户端（订单 / 客户 / 商品服务内部接口）
    @Autowired
    private OrderInternalClient orderInternalClient;
    @Autowired
    private CustomerInternalClient customerInternalClient;
    @Autowired
    private ProductInternalClient productInternalClient;

    // ==================== 查询（分页 + 缓存） ====================

    /**
     * 分页查询商品评价（带客户信息）
     * <p>
     * 微服务拆分说明（列表查询改造）：原实现走 Redis 缓存
     * {@code cache:review:product:{productId}}（TTL 300s）+ 回源查库，
     * 任意评价增删都要失效该商品与客户的评价缓存。
     * 改造后直接查 shopai-search 的 review 索引（按 createTime 倒序分页），
     * 索引里已冗余评价人昵称/头像，无需再跨服务补齐。
     */
    @Override
    public Map<String, Object> getReviewsByProductId(Long productId, int page, int size) {
        MerchantListIndexService.ReviewPage reviewPage =
                merchantListIndexService.queryReviewsByProductId(productId, page, size);
        int offset = (page - 1) * size;
        boolean hasMore = offset + size < reviewPage.getTotal();

        Map<String, Object> result = new HashMap<>();
        result.put("list", reviewPage.getList());
        result.put("total", reviewPage.getTotal());
        result.put("hasMore", hasMore);
        return result;
    }

    /**
     * 获取商品评分统计（平均分 + 星级分布）
     */
    @Override
    public Map<String, Object> getProductRatingStats(Long productId) {
        String cacheKey = RedisService.CACHE_REVIEW_PRODUCT + productId + ":stats";
        return redisService.getOrSetTyped(
                cacheKey,
                new TypeReference<Map<String, Object>>() {},
                300L,
                () -> {
                    List<Map<String, Object>> rows = reviewMapper.countRatingByProductId(productId);

                    Map<Integer, Long> distribution = new LinkedHashMap<>();
                    for (int i = 5; i >= 1; i--) distribution.put(i, 0L);
                    long totalCount = 0;
                    double totalScore = 0;
                    for (Map<String, Object> row : rows) {
                        int rating = (int) row.get("rating");
                        long cnt = ((Number) row.get("cnt")).longValue();
                        distribution.put(rating, cnt);
                        totalCount += cnt;
                        totalScore += (long) rating * cnt;
                    }
                    double avgRating = totalCount > 0 ? totalScore / totalCount : 0;
                    avgRating = Math.round(avgRating * 10.0) / 10.0;

                    Map<String, Object> stats = new HashMap<>();
                    stats.put("average", avgRating);
                    stats.put("totalCount", totalCount);
                    stats.put("distribution", distribution);
                    return stats;
                }
        );
    }

    /**
     * 根据客户ID查询评价列表
     * <p>
     * 微服务拆分说明（列表查询改造）：原走 Redis 缓存 {@code cache:review:customer:{id}}，
     * 改造后直接查 review 索引。
     */
    @Override
    public List<Review> getReviewsByCustomerId(Long customerId) {
        if (customerId == null) {
            return Collections.emptyList();
        }
        return merchantListIndexService.queryReviewsByCustomerId(customerId);
    }

    /**
     * 根据订单ID查询评价
     */
    @Override
    public Review getReviewByOrderId(Long orderId) {
        return reviewMapper.selectByOrderId(orderId);
    }

    /**
     * 判断订单是否已评价
     */
    @Override
    public boolean existsByOrderId(Long orderId) {
        return reviewMapper.existsByOrderId(orderId) > 0;
    }

    /**
     * 管理员分页查评价
     * <p>
     * 微服务拆分说明（列表查询改造）：原实现查库并靠 ReviewMapper.xml 的
     * {@code LEFT JOIN customers / products / merchants} 补展示字段；
     * 改造后直接查 review 索引（这些字段在回源组装时已冗余进索引）。
     */
    @Override
    public Map<String, Object> getAdminReviewsPage(int page, int size) {
        MerchantListIndexService.ReviewPage reviewPage =
                merchantListIndexService.queryAllReviews(page, size);
        int offset = (page - 1) * size;
        boolean hasMore = offset + size < reviewPage.getTotal();

        Map<String, Object> result = new HashMap<>();
        result.put("list", reviewPage.getList());
        result.put("total", reviewPage.getTotal());
        result.put("hasMore", hasMore);
        return result;
    }

    /**
     * 管理员按评论ID搜索
     * <p>
     * 说明：这是「按主键取单条」，属点查而非列表查询，继续走数据库（走主键索引，代价极低），
     * 再按需补齐展示字段。列表才需要走 ES。
     * <p>
     * 微服务拆分说明：跨库 JOIN 已改为跨服务补齐（见 {@link #enrichReviews}）。
     */
    @Override
    public Review getReviewByIdWithDetails(Long id) {
        Review review = reviewMapper.selectByIdWithDetails(id);
        if (review != null) {
            enrichReviews(Collections.singletonList(review), true, true, true);
        }
        return review;
    }

    /**
     * 按主键取单条评价（不做展示信息补齐）——仅供归属/权限校验使用
     */
    @Override
    public Review getReviewById(Long id) {
        return id == null ? null : reviewMapper.selectById(id);
    }

    // ==================== 跨服务：评价展示信息补齐（Feign 替代跨库 JOIN） ====================
    /**
     * 为评价列表补齐展示信息
     * <p>
     * 微服务拆分说明：替代原 {@code ReviewMapper.xml} 中的三段跨库 JOIN：
     * - customer（customers 表，归属 shopai-user）→ Feign 调用户服务客户内部接口
     * - productName（products 表，归属 shopai-product）→ Feign 调商品服务内部接口
     * - merchantName（merchants 表，本服务）→ 本地 MerchantMapper
     * <p>
     * 每类信息各一次批量调用，避免 N+1；任一依赖服务不可用时对应字段留空，
     * 评价本身信息照常返回（避免跨服务雪崩）。
     *
     * @param reviews       评价列表
     * @param withMerchant  是否补齐商家名
     * @param withProduct   是否补齐商品名
     * @param withCustomer  是否补齐客户信息
     */
    private void enrichReviews(List<Review> reviews, boolean withMerchant, boolean withProduct, boolean withCustomer) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }

        // 1. 客户信息（替代 LEFT JOIN customers）
        if (withCustomer) {
            Map<Long, CustomerInfo> customerMap = loadCustomers(reviews);
            for (Review r : reviews) {
                CustomerInfo info = r.getCustomerId() == null ? null : customerMap.get(r.getCustomerId());
                if (info != null) {
                    CustomerRef ref = new CustomerRef();
                    ref.setId(info.getId());
                    ref.setUserId(info.getUserId());
                    ref.setNickname(info.getNickname());
                    ref.setAvatarUrl(info.getAvatarUrl());
                    r.setCustomer(ref);
                }
            }
        }

        // 2. 商品名（替代 LEFT JOIN products）
        if (withProduct) {
            Map<Long, String> productNames = loadProductNames(reviews);
            for (Review r : reviews) {
                if (r.getProductId() != null) {
                    r.setProductName(productNames.get(r.getProductId()));
                }
            }
        }

        // 3. 商家名（原本服务 JOIN merchants，仅为实现统一也在此补齐）
        if (withMerchant) {
            Map<Long, String> merchantNames = loadMerchantNames(reviews);
            for (Review r : reviews) {
                if (r.getMerchantId() != null) {
                    r.setMerchantName(merchantNames.get(r.getMerchantId()));
                }
            }
        }
    }

    /** 批量取客户信息（Feign 调用户服务；降级返回空 Map） */
    private Map<Long, CustomerInfo> loadCustomers(List<Review> reviews) {
        Map<Long, CustomerInfo> map = new HashMap<>();
        String ids = reviews.stream()
                .map(Review::getCustomerId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<CustomerInfo>> r = customerInternalClient.listCustomers(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (CustomerInfo c : r.getData()) {
                    if (c.getId() != null) {
                        map.put(c.getId(), c);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取客户信息失败（评价将缺少昵称/头像），customerIds={}", ids, e);
        }
        return map;
    }

    /** 批量取商品名（Feign 调商品服务；降级返回空 Map） */
    private Map<Long, String> loadProductNames(List<Review> reviews) {
        Map<Long, String> map = new HashMap<>();
        String ids = reviews.stream()
                .map(Review::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<ProductBrief>> r = productInternalClient.listProductBriefs(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (ProductBrief p : r.getData()) {
                    if (p.getId() != null) {
                        map.put(Long.valueOf(p.getId()), p.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取商品名失败（评价将缺少商品名），productIds={}", ids, e);
        }
        return map;
    }

    /** 批量取商家名（本服务本地查询，无需跨服务） */
    private Map<Long, String> loadMerchantNames(List<Review> reviews) {
        Map<Long, String> map = new HashMap<>();
        List<Long> merchantIds = reviews.stream()
                .map(Review::getMerchantId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (merchantIds.isEmpty()) {
            return map;
        }
        try {
            List<Merchant> merchants = merchantMapper.selectBatchIds(merchantIds);
            for (Merchant m : merchants) {
                map.put(m.getId(), m.getShopName());
            }
        } catch (Exception e) {
            log.error("批量获取商家名失败（评价将缺少商家名），merchantIds={}", merchantIds, e);
        }
        return map;
    }

    // ==================== 写操作 ====================

    /**
     * 新增评价（一个订单只能评价一次，同步标记 is_reviewed）
     * <p>
     * 微服务拆分说明：orders 表归属 shopai-order 服务（独立库），原 OrderRefMapper
     * 跨库直查/直写已改为 Feign 调订单服务内部接口：
     * - 二次校验：GET /api/order/internal/{orderId} 取 is_reviewed；
     * - 落库后标记：POST /api/order/internal/{orderId}/reviewed。
     */
    @Override
    @Transactional
    public void addReview(Review review) {
        if (existsByOrderId(review.getOrderId())) {
            throw new RuntimeException("该订单已评价");
        }

        // 二次校验：数据库层面标记 is_reviewed（跨服务查询订单服务）
        OrderInfo order = getOrderViaFeign(review.getOrderId());
        if (order != null && order.getIsReviewed() != null && order.getIsReviewed() == 1) {
            throw new RuntimeException("该订单已评价");
        }

        review.setCreateTime(java.time.LocalDateTime.now());
        reviewMapper.insert(review);

        // 标记订单已评价（跨服务调用订单服务）
        if (order != null) {
            markOrderReviewedViaFeign(review.getOrderId());
        }

        evictReviewCacheAfterCommit(review.getProductId(), review.getCustomerId());
        // 通知搜索服务把该评价写入/更新 review 索引（列表查询改造）。
        // 事务安全：必须放到**事务提交后**。原实现在事务内直接 publish，
        // 若本事务随后回滚（例如下面的评分更新抛异常），搜索侧已经把这条并不存在的评价
        // 写进了 review 索引 —— 列表里会出现一条数据库里查不到的评价。
        if (review.getId() != null) {
            Long reviewId = review.getId();
            AfterCommitExecutor.run(
                    () -> indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.REVIEW, reviewId));
        }
        updateMerchantRatingAfterCommit(review.getMerchantId());
    }

    // ==================== 跨服务：订单相关（Feign 替代跨库直查直写） ====================

    /**
     * 调订单服务查询订单
     * <p>
     * 原实现：{@code orderRefMapper.selectById(review.getOrderId())}（跨库直查 orders）。
     * 降级策略：订单服务不可用时返回 null，本次不做「已评价」二次校验，
     * 但 Insert 前已通过本地 {@code existsByOrderId} 做过一次校验，风险可控。
     */
    private OrderInfo getOrderViaFeign(Long orderId) {
        if (orderId == null) {
            return null;
        }
        try {
            Result<OrderInfo> r = orderInternalClient.getOrder(orderId);
            if (r != null && r.getCode() != null && r.getCode() == 200) {
                return r.getData();
            }
        } catch (Exception e) {
            log.error("调用订单服务查询订单失败, orderId={}", orderId, e);
        }
        return null;
    }

    /**
     * 调订单服务标记订单已评价
     * <p>
     * 原实现：{@code orderRefMapper.updateById(order)}（把 is_reviewed 置 1，跨库直写）。
     * 说明：此处不抛出异常——评价已落库，标记失败只影响「重复评价」的二次拦截，
     * 由 {@code existsByOrderId} 继续兜底；抛出会因事务回滚而丢掉用户评价。
     */
    private void markOrderReviewedViaFeign(Long orderId) {
        try {
            Result<Integer> r = orderInternalClient.markReviewed(orderId);
            if (r == null || r.getCode() == null || r.getCode() != 200) {
                log.warn("标记订单已评价失败, orderId={}, resp={}", orderId, r == null ? null : r.getMessage());
            }
        } catch (Exception e) {
            log.error("调用订单服务标记已评价失败, orderId={}", orderId, e);
        }
    }

    /**
     * 管理员删除评价（物理删除，is_reviewed 标记保持为1，防止顾客再次评价）
     * <p>
     * 微服务拆分说明（列表查询改造）：删除后除清理缓存外，还需通知搜索服务
     * 从 review 索引移除该文档（原实现靠清 Redis 评价列表缓存达到同样效果）。
     */
    @Override
    @Transactional
    public void deleteReview(Long id) {
        Review review = reviewMapper.selectById(id);
        if (review == null) return;

        reviewMapper.deleteById(id);
        evictReviewCacheAfterCommit(review.getProductId(), review.getCustomerId());
        // 通知搜索服务删除该评价索引文档。
        // 事务安全：提交后再发，避免「删除回滚但索引已移除」导致评价在列表里消失。
        AfterCommitExecutor.run(
                () -> indexEventPublisher.publishDelete(IndexChangedEvent.EntityType.REVIEW, id));
        updateMerchantRatingAfterCommit(review.getMerchantId());
    }

    // ==================== 商家评分计算 ====================

    /**
     * 更新商家评分（所有评价均分，0-5，小数点后1位）
     */
    private void updateMerchantRatingAfterCommit(Long merchantId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            updateMerchantRating(merchantId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        updateMerchantRating(merchantId);
                    }
                }
        );
    }

    private void updateMerchantRating(Long merchantId) {
        try {
            List<Integer> ratings = reviewMapper.selectRatingsByMerchantId(merchantId);
            if (ratings == null || ratings.isEmpty()) {
                merchantService.updateRating(merchantId, new BigDecimal("5.00"));
                return;
            }
            double avg = ratings.stream().mapToInt(Integer::intValue).average().orElse(5.0);
            avg = Math.round(avg * 10.0) / 10.0;
            if (avg < 0) avg = 0;
            if (avg > 5) avg = 5;
            merchantService.updateRating(merchantId, BigDecimal.valueOf(avg));
            log.info("商家评分更新: merchantId={}, rating={}", merchantId, avg);
        } catch (Exception e) {
            log.error("更新商家评分失败: merchantId={}", merchantId, e);
        }
    }

    // ==================== 缓存失效 ====================

    private void evictReviewCacheAfterCommit(Long productId, Long customerId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictReviewCacheNow(productId, customerId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        evictReviewCacheNow(productId, customerId);
                    }
                }
        );
    }

    /**
     * 评价变更后的 Redis 缓存清理
     * <p>
     * 列表查询改造说明：原实现会清两类评价列表缓存
     * （{@code CACHE_REVIEW_PRODUCT}、{@code CACHE_REVIEW_CUSTOMER}）。
     * 评价列表已改查 ES 索引（见 {@link MerchantListIndexService}），
     * 因此这里**不再清理评价列表缓存**。
     * <p>
     * 保留的清理项：{@code CACHE_REVIEW_PRODUCT + productId + ":stats"} ——
     * 商品评分统计（平均分 + 星级分布）是聚合统计，仍由本服务本地
     * {@code COUNT/GROUP BY} 提供并缓存，必须随评价增删失效。
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor} 统一「提交后执行」语义。
     * 原实现虽已用 registerSynchronization，但这里是评价链路的公共清理入口，
     * 统一到同一工具便于后续维护。
     */
    private void evictReviewCacheNow(Long productId, Long customerId) {
        if (productId != null) {
            redisService.deleteKey(RedisService.CACHE_REVIEW_PRODUCT + productId + ":stats");
        }

        cacheDelayScheduler.schedule(() -> {
            try {
                if (productId != null) {
                    redisService.deleteKey(RedisService.CACHE_REVIEW_PRODUCT + productId + ":stats");
                }
            } catch (Exception e) {
                log.error("延迟双删评价统计缓存失败", e);
            }
        }, 300, TimeUnit.MILLISECONDS);
    }
}
