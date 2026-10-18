package com.shopai.merchant.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.merchant.entity.Review;
import com.shopai.merchant.service.CustomerResolver;
import com.shopai.merchant.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 商品评价控制器
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.controller.ReviewController 迁移至 shopai-merchant 服务。
 * 路径保持不变（/api/review），改造点：
 * 1. 鉴权方式：原通过 token 解析 userId + customerService.UserIdes(userId) 映射 customerId；
 *    拆分后 userId 由网关 X-User-Id 头提供，customerId 通过 CustomerResolver 映射
 *    （内部 Feign 调用户服务客户内部接口；customers 表归属 shopai-user 服务，独立库后不再直查）。
 * 2. 管理员删除评价：原无鉴权校验，拆分后由网关统一校验 /api/admin/** 前缀（此处保持原逻辑，
 *    未挂 admin 前缀，删除接口 /api/review/{id} 由网关放行策略兜底）。
 */
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    // 微服务拆分：原 CustomerRefMapper（直查 customers 表）→ CustomerResolver（Feign 调用户服务）
    private final CustomerResolver customerResolver;
    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    // ==================== 查询 ====================

    /** 分页查询商品评价（带客户信息） */
    @GetMapping("/product/{productId}")
    public Result<?> getReviewsByProductId(@PathVariable Long productId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> data = reviewService.getReviewsByProductId(productId, page, size);
        return Result.ok("商品评价列表", data);
    }

    /** 商品评分统计（平均分 + 各星级分布） */
    @GetMapping("/product/{productId}/stats")
    public Result<?> getProductRatingStats(@PathVariable Long productId) {
        Map<String, Object> stats = reviewService.getProductRatingStats(productId);
        return Result.ok("评分统计", stats);
    }

    /**
     * 我的评价
     * <p>微服务拆分说明：原用 customerService.UserIdes(userId) 映射 customerId，
     * 后改为 customerRefMapper.selectIdByUserId(userId)（共享单库直查），
     * 现改为 CustomerResolver（Feign 调用户服务客户内部接口）。
     */
    @GetMapping("/customer")
    public Result<?> getMyReviews(HttpServletRequest request) {
        Long userId = CurrentUser.getUserId(request);
        Long customerId = customerResolver.toCustomerId(userId);
        List<Review> reviews = reviewService.getReviewsByCustomerId(customerId);
        return Result.ok("我的评价", reviews);
    }

    /** 查某订单的评价（判断是否已评） */
    @GetMapping("/order/{orderId}")
    public Result<?> getReviewByOrderId(@PathVariable Long orderId) {
        Review review = reviewService.getReviewByOrderId(orderId);
        if (review == null) {
            return Result.ok("该订单尚未评价", null);
        }
        return Result.ok(review);
    }

    /** 判断订单是否已评价 */
    @GetMapping("/exists/{orderId}")
    public Result<?> existsByOrderId(@PathVariable Long orderId) {
        boolean exists = reviewService.existsByOrderId(orderId);
        return Result.ok(exists ? "已评价" : "未评价", exists);
    }

    /** 管理员分页查所有评价 */
    @GetMapping("/admin/list")
    public Result<?> adminList(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> data = reviewService.getAdminReviewsPage(page, size);
        return Result.ok("所有评价", data);
    }

    /** 管理员按评论ID搜索评价 */
    @GetMapping("/admin/search")
    public Result<?> adminSearch(@RequestParam Long id) {
        Review review = reviewService.getReviewByIdWithDetails(id);
        if (review == null) {
            return Result.ok("未找到该评论", null);
        }
        return Result.ok(review);
    }

    // ==================== 写操作 ====================

    /**
     * 新增评价
     * <p>微服务拆分说明：原用 customerService.UserIdes(userId) 映射 customerId，
     * 后改为 customerRefMapper.selectIdByUserId(userId)（共享单库直查），
     * 现改为 CustomerResolver（Feign 调用户服务客户内部接口）。
     */
    @PostMapping("/add")
    public Result<?> addReview(HttpServletRequest request,
                               @RequestBody Review review) {
        Long userId = CurrentUser.getUserId(request);
        Long customerId = customerResolver.toCustomerId(userId);

        if (review.getCustomerId() == null) {
            review.setCustomerId(customerId);
        } else if (!review.getCustomerId().equals(customerId)) {
            return Result.error("无权操作，只能以自己的身份评价");
        }

        try {
            reviewService.addReview(review);
            log.info("新增评价: customerId={}, productId={}, rating={}", customerId, review.getProductId(), review.getRating());
            return Result.ok("评价发布成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除评价
     * <p>
     * 权限修复（越权删除）：原实现是 {@code deleteReview(@PathVariable Long id)} ——
     * <b>不接收 request、既无角色校验也无归属校验</b>，任何登录用户（顾客/商家）
     * 只要遍历 id 就能删掉任意评价。实测：顾客 token 调
     * {@code DELETE /api/review/999999} 直接返回「操作成功」。
     * <p>
     * 现在的规则：
     * <ul>
     *   <li>管理员 —— 可删除任意评价（后台「评价管理」需要）；</li>
     *   <li>其他人 —— 只能删除自己写的评价（商品详情页有「删除我的评价」入口）。</li>
     * </ul>
     * 无权时统一返回 403，且不区分「评价是否存在」/「是否别人的」，
     * 避免通过返回值探测评价 id。
     */
    @DeleteMapping("/{id}")
    public Result<?> deleteReview(HttpServletRequest request, @PathVariable Long id) {
        String role = CurrentUser.getRole(request);
        boolean isAdmin = "admin".equalsIgnoreCase(role);

        if (!isAdmin) {
            Long customerId = customerResolver.toCustomerId(CurrentUser.getUserId(request));
            Review target = reviewService.getReviewById(id);
            if (customerId == null || target == null || !customerId.equals(target.getCustomerId())) {
                log.warn("评价删除越权已拦截: reviewId={}, userId={}, role={}",
                        id, CurrentUser.getUserId(request), role);
                return Result.error(403, "无权操作");
            }
        }

        try {
            reviewService.deleteReview(id);
            log.info("删除评价: id={}, operatorRole={}", id, role);
            return Result.ok("评价已删除");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
