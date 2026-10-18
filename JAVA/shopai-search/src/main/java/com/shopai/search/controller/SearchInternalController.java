package com.shopai.search.controller;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.ProductIndexItem;
import com.shopai.common.dto.internal.index.OrderIndexItem;
import com.shopai.common.dto.internal.index.ReviewIndexItem;
import com.shopai.common.dto.internal.index.UserIndexItem;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.search.document.OrderDocument;
import com.shopai.search.document.ProductDocument;
import com.shopai.search.document.ReviewDocument;
import com.shopai.search.document.UserDocument;
import com.shopai.search.repository.MerchantDocumentRepository;
import com.shopai.search.repository.OrderDocumentRepository;
import com.shopai.search.repository.ProductDocumentRepository;
import com.shopai.search.repository.ReviewDocumentRepository;
import com.shopai.search.repository.UserDocumentRepository;
import com.shopai.search.service.IndexListQueryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索服务内部接口（供其他服务通过 Feign 调用，不对网关暴露）
 * <p>
 * 微服务拆分说明（列表查询改造）：本控制器是「列表查询 ES 化」的枢纽，承担两类职责：
 * <p>
 * 1. **写侧（各归属服务 → 索引）**：数据归属方在实体变更后发 MQ，搜索服务消费后
 *    通过 Feign 回源调用下面这些 {@code /upsert/**} 接口，由归属方把「索引视图」
 *    （含跨服务字段，如店铺名、分类名、评价人昵称）组装好回传，本服务写入 ES。
 *    把组装职责放在归属方，是因为只有归属方知道自己的业务字段与关联关系；
 *    搜索服务只做「存 + 查」，不反向依赖各业务的组装逻辑。
 *    注：{@code /upsert/product} 与 {@code /upsert/merchant} 由既有的
 *    SearchSyncService 通过调用各服务的 {@code /internal/index/**} 接口拉取，
 *    故此处只保留读写对称的新增三类（order/review/user）的写入口。
 * <p>
 * 2. **读侧（各业务服务的列表 → ES）**：{@code POST /list} 是统一列表查询入口，
 *    各业务服务保留自己的对外路由，内部调本接口完成过滤/检索/排序/分页。
 * <p>
 * 安全说明：{@code /api/search/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@RestController
@RequestMapping(InternalApiPaths.SEARCH_INTERNAL)
@RequiredArgsConstructor
public class SearchInternalController {

    private static final Logger log = LoggerFactory.getLogger(SearchInternalController.class);

    private final IndexListQueryService indexListQueryService;
    private final OrderDocumentRepository orderRepository;
    private final ReviewDocumentRepository reviewRepository;
    private final UserDocumentRepository userRepository;
    private final ProductDocumentRepository productRepository;
    private final MerchantDocumentRepository merchantRepository;

    // ==================== 读侧：统一列表查询 ====================

    /**
     * 统一列表查询（过滤 + 全文检索 + 排序 + 分页）
     * <p>
     * 调用方：shopai-product（商品列表/热销榜）、shopai-merchant（商家分页/评价列表）、
     * shopai-order（顾客/商家/管理员订单列表）、shopai-user（后台用户列表）。
     */
    @PostMapping("/list")
    public Result<IndexListResult> list(@RequestBody IndexListQuery query) {
        try {
            return Result.ok(indexListQueryService.list(query));
        } catch (IllegalArgumentException e) {
            // 参数问题（索引类型/过滤字段非法）属调用方错误，直接回错误码
            log.warn("列表查询参数非法: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("列表查询失败, indexType={}", query == null ? null : query.getIndexType(), e);
            return Result.error("列表查询失败：" + e.getMessage());
        }
    }

    // ==================== 写侧：索引文档 upsert / delete ====================

    /**
     * 写入/更新订单索引文档（订单服务回源调用）
     */
    @PostMapping("/upsert/order")
    public Result<?> upsertOrder(@RequestBody OrderIndexItem item) {
        if (item == null || item.getId() == null) {
            return Result.error("订单索引数据不完整（id 为空）");
        }
        orderRepository.save(toOrderDocument(item));
        log.info("订单索引已更新: orderId={}, status={}", item.getId(), item.getStatus());
        return Result.ok("订单索引已更新");
    }

    /**
     * 删除订单索引文档
     */
    @DeleteMapping("/order/{id}")
    public Result<?> deleteOrder(@PathVariable("id") String id) {
        orderRepository.deleteById(id);
        log.info("订单索引已删除: orderId={}", id);
        return Result.ok("订单索引已删除");
    }

    /**
     * 写入/更新评价索引文档（商家服务回源调用）
     */
    @PostMapping("/upsert/review")
    public Result<?> upsertReview(@RequestBody ReviewIndexItem item) {
        if (item == null || item.getId() == null) {
            return Result.error("评价索引数据不完整（id 为空）");
        }
        reviewRepository.save(toReviewDocument(item));
        log.info("评价索引已更新: reviewId={}, productId={}", item.getId(), item.getProductId());
        return Result.ok("评价索引已更新");
    }

    /**
     * 删除评价索引文档
     */
    @DeleteMapping("/review/{id}")
    public Result<?> deleteReview(@PathVariable("id") String id) {
        reviewRepository.deleteById(id);
        log.info("评价索引已删除: reviewId={}", id);
        return Result.ok("评价索引已删除");
    }

    /**
     * 写入/更新用户索引文档（用户服务回源调用）
     * <p>
     * 安全说明：传入的 {@link UserIndexItem} 不含凭据字段，索引中也不会出现密码哈希。
     */
    @PostMapping("/upsert/user")
    public Result<?> upsertUser(@RequestBody UserIndexItem item) {
        if (item == null || item.getId() == null) {
            return Result.error("用户索引数据不完整（id 为空）");
        }
        userRepository.save(toUserDocument(item));
        log.info("用户索引已更新: userId={}, role={}, status={}", item.getId(), item.getRole(), item.getStatus());
        return Result.ok("用户索引已更新");
    }

    /**
     * 删除用户索引文档（销号）
     */
    @DeleteMapping("/user/{id}")
    public Result<?> deleteUser(@PathVariable("id") String id) {
        userRepository.deleteById(id);
        log.info("用户索引已删除: userId={}", id);
        return Result.ok("用户索引已删除");
    }

    // ==================== 写侧：商品/商家索引 upsert（既有实现的补充） ====================

    /**
     * 批量写入/更新商品索引文档（商品服务回源调用）
     * <p>
     * 用途：**分类改名后的扇出同步**。product 索引里的
     * {@code categoryName / parentCategoryName} 是从 categories 表冗余过去的，
     * 分类改名后这些字段会陈旧；此时商品服务按 categoryId 找出受影响商品，
     * 一次性把它们的索引视图推过来批量 upsert。
     * <p>
     * 为什么需要「批量」入口：逐条发 MQ 会带来 N 次消息投递 + N 次回源 Feign，
     * 对「一次分类改名影响成百上千商品」的场景代价过高；批量接口把 N 次往返压成 1 次。
     *
     * @param items 商品索引视图列表（由商品服务组装好店铺名/分类名）
     * @return 写入条数
     */
    @PostMapping("/upsert/products")
    public Result<Integer> upsertProducts(@RequestBody List<ProductIndexItem> items) {
        if (items == null || items.isEmpty()) {
            return Result.ok(0);
        }
        int count = 0;
        for (ProductIndexItem item : items) {
            if (item == null || item.getId() == null) {
                continue;
            }
            productRepository.save(toProductDocument(item));
            count++;
        }
        log.info("商品索引批量更新完成: {} 条", count);
        return Result.ok(count);
    }

    // ==================== 全量重建（含新增三类索引） ====================

    /**
     * 全量重建订单索引
     * <p>
     * 由订单服务的内部接口提供全量数据，本服务负责写入（读写分离在搜索侧统一收口）。
     */
    @PostMapping("/rebuild/order")
    public Result<Integer> rebuildOrder(@RequestBody List<OrderIndexItem> items) {
        List<OrderDocument> docs = new ArrayList<>();
        if (items != null) {
            for (OrderIndexItem item : items) {
                if (item != null && item.getId() != null) {
                    docs.add(toOrderDocument(item));
                }
            }
        }
        orderRepository.deleteAll();
        orderRepository.saveAll(docs);
        log.info("订单索引全量重建完成: {} 条", docs.size());
        return Result.ok(docs.size());
    }

    /**
     * 全量重建评价索引
     */
    @PostMapping("/rebuild/review")
    public Result<Integer> rebuildReview(@RequestBody List<ReviewIndexItem> items) {
        List<ReviewDocument> docs = new ArrayList<>();
        if (items != null) {
            for (ReviewIndexItem item : items) {
                if (item != null && item.getId() != null) {
                    docs.add(toReviewDocument(item));
                }
            }
        }
        reviewRepository.deleteAll();
        reviewRepository.saveAll(docs);
        log.info("评价索引全量重建完成: {} 条", docs.size());
        return Result.ok(docs.size());
    }

    /**
     * 全量重建用户索引
     */
    @PostMapping("/rebuild/user")
    public Result<Integer> rebuildUser(@RequestBody List<UserIndexItem> items) {
        List<UserDocument> docs = new ArrayList<>();
        if (items != null) {
            for (UserIndexItem item : items) {
                if (item != null && item.getId() != null) {
                    docs.add(toUserDocument(item));
                }
            }
        }
        userRepository.deleteAll();
        userRepository.saveAll(docs);
        log.info("用户索引全量重建完成: {} 条", docs.size());
        return Result.ok(docs.size());
    }

    /**
     * 索引健康检查：返回五个列表索引的文档数
     * <p>
     * 用途：全量重建后确认索引就绪；也可在排查「列表为空」时快速定位是索引没数据
     * 还是查询条件不对。
     */
    @GetMapping("/stats")
    public Result<java.util.Map<String, Long>> stats() {
        java.util.Map<String, Long> stats = new java.util.LinkedHashMap<>();
        stats.put("product", productRepository.count());
        stats.put("merchant", merchantRepository.count());
        stats.put("order", orderRepository.count());
        stats.put("review", reviewRepository.count());
        stats.put("user", userRepository.count());
        return Result.ok(stats);
    }

    // ==================== 转换 ====================

    /**
     * ProductIndexItem（跨服务 DTO）→ ProductDocument（ES 文档）
     * <p>
     * 与 {@code SearchSyncService.toProductDocument} 保持一致：商品服务在组装索引视图时
     * 已把店铺名/Logo（Feign 调商家服务）、分类名/父分类名（本地联查）填好，
     * 搜索服务只做字段搬运。
     */
    private ProductDocument toProductDocument(ProductIndexItem ref) {
        return ProductDocument.builder()
                .id(ref.getId())
                .name(ref.getName())
                .description(ref.getDescription())
                .mainImage(ref.getMainImage())
                .categoryId(ref.getCategoryId())
                .categoryName(ref.getCategoryName())
                .parentCategoryName(ref.getParentCategoryName())
                .merchantId(ref.getMerchantId())
                .shopName(ref.getShopName())
                .shopLogo(ref.getShopLogo())
                .priceMin(ref.getPriceMin())
                .priceMax(ref.getPriceMax())
                .salesCount(ref.getSalesCount())
                .status(ref.getStatus())
                .createdAt(ref.getCreatedAt())
                // 商品服务同时返回 imageList（List<String>）与 images（JSON 字符串），
                // ES 文档字段为 List<String>，取 imageList；缺失时退回空列表
                .images(ref.getImageList() != null ? ref.getImageList() : java.util.Collections.emptyList())
                .build();
    }

    private OrderDocument toOrderDocument(OrderIndexItem i) {
        return OrderDocument.builder()
                .id(i.getId())
                .orderNo(i.getOrderNo())
                .customerId(i.getCustomerId())
                .customerNickname(i.getCustomerNickname())
                .merchantId(i.getMerchantId())
                .shopName(i.getShopName())
                .shopLogo(i.getShopLogo())
                .totalAmount(i.getTotalAmount())
                .payAmount(i.getPayAmount())
                .freightAmount(i.getFreightAmount())
                .status(i.getStatus())
                .isReviewed(i.getIsReviewed())
                .finalized(i.getFinalized())
                .itemCount(i.getItemCount())
                .itemSummary(i.getItemSummary())
                .itemImage(i.getItemImage())
                .paidAt(i.getPaidAt())
                .shippedAt(i.getShippedAt())
                .completedAt(i.getCompletedAt())
                .createTime(i.getCreateTime())
                .build();
    }

    private ReviewDocument toReviewDocument(ReviewIndexItem i) {
        return ReviewDocument.builder()
                .id(i.getId())
                .orderId(i.getOrderId())
                .customerId(i.getCustomerId())
                .customerNickname(i.getCustomerNickname())
                .customerAvatar(i.getCustomerAvatar())
                .productId(i.getProductId())
                .productName(i.getProductName())
                .merchantId(i.getMerchantId())
                .merchantName(i.getMerchantName())
                .rating(i.getRating())
                .content(i.getContent())
                .images(i.getImages())
                .createTime(i.getCreateTime())
                .build();
    }

    private UserDocument toUserDocument(UserIndexItem i) {
        return UserDocument.builder()
                .id(i.getId())
                .username(i.getUsername())
                .phone(i.getPhone())
                .email(i.getEmail())
                .role(i.getRole())
                .status(i.getStatus())
                .customerNickname(i.getCustomerNickname())
                .shopName(i.getShopName())
                .createTime(i.getCreateTime())
                .updateTime(i.getUpdateTime())
                .build();
    }
}
