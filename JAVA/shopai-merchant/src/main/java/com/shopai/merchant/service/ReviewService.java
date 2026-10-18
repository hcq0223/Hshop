package com.shopai.merchant.service;

import com.shopai.merchant.entity.Review;

import java.util.List;
import java.util.Map;

/**
 * 商品评价服务接口
 * 缓存策略：商品评价列表（TTL 300s）、客户评价列表（TTL 180s）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.ReviewService 迁移至 shopai-merchant 服务。
 * 主要改造点：
 * 1. 原注入 OrderMapper（orders 表归属 shopai-order）→ 改为 OrderRefMapper + OrderRef 实体（共享单库直查/直写）。
 * 2. 原注入 MerchantService.updateRating 保持不变（merchants 表归属本服务）。
 * <p>
 * 分层改造（2026-10-05）：本接口 + ReviewServiceImpl（extends ServiceImpl&lt;ReviewMapper, Review&gt;）标准分层。
 */
public interface ReviewService {

    /**
     * 分页查询商品评价（带客户信息）
     */
    Map<String, Object> getReviewsByProductId(Long productId, int page, int size);

    /**
     * 获取商品评分统计（平均分 + 星级分布）
     */
    Map<String, Object> getProductRatingStats(Long productId);

    /**
     * 根据客户ID查询评价列表
     */
    List<Review> getReviewsByCustomerId(Long customerId);

    /**
     * 根据订单ID查询评价
     */
    Review getReviewByOrderId(Long orderId);

    /**
     * 判断订单是否已评价
     */
    boolean existsByOrderId(Long orderId);

    /**
     * 管理员分页查评价（Redis缓存 300s，删评/新增时失效）
     */
    Map<String, Object> getAdminReviewsPage(int page, int size);

    /**
     * 管理员按评论ID搜索
     */
    Review getReviewByIdWithDetails(Long id);

    /**
     * 按主键取单条评价（**不做任何展示信息补齐**）。
     * <p>
     * 专供「归属校验」这类只关心 {@code customerId / merchantId} 的场景使用。
     * 不要用 {@link #getReviewByIdWithDetails(Long)} —— 它会为一条记录发起
     * 客户/商品/商家三次补齐调用，放在鉴权路径上纯属浪费。
     */
    Review getReviewById(Long id);

    /**
     * 新增评价（一个订单只能评价一次，同步标记 is_reviewed）
     * <p>
     * 微服务拆分说明：原注入 OrderMapper + Order 实体，拆分后改为 OrderRefMapper + OrderRef
     * （orders 表归属订单服务，共享单库直查/直写，后续拆库改 Feign）。
     */
    void addReview(Review review);

    /**
     * 管理员删除评价（物理删除，is_reviewed 标记保持为1，防止顾客再次评价）
     */
    void deleteReview(Long id);
}
