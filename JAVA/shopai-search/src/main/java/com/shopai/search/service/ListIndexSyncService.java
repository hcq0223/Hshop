package com.shopai.search.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.index.OrderIndexItem;
import com.shopai.common.dto.internal.index.ReviewIndexItem;
import com.shopai.common.dto.internal.index.UserIndexItem;
import com.shopai.search.document.OrderDocument;
import com.shopai.search.document.ReviewDocument;
import com.shopai.search.document.UserDocument;
import com.shopai.search.feign.OrderSourceClient;
import com.shopai.search.feign.ReviewSourceClient;
import com.shopai.search.feign.UserSourceClient;
import com.shopai.search.repository.OrderDocumentRepository;
import com.shopai.search.repository.ReviewDocumentRepository;
import com.shopai.search.repository.UserDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 列表索引同步服务（order / review / user 三类索引）
 * <p>
 * 微服务拆分说明（列表查询改造）：这三类实体原先都靠 Redis 列表缓存
 * （{@code cache:order:list:*}、{@code cache:review:product:*}、{@code cache:user:list:*}），
 * 任意一条数据变更即整类列表缓存全清。改造后由本服务维护对应 ES 索引：
 * <p>
 * - 写：消费 MQ 事件（{@code order.changed} / {@code review.changed} / {@code user.changed}），
 *   通过各归属服务的内部接口「回源」取索引视图，再写入 ES；
 * - 读：由 {@link IndexListQueryService} 承担，各业务服务通过 Feign 查询。
 * <p>
 * 为什么「回源」而不是让事件携带全量数据：见 {@link com.shopai.common.mq.IndexChangedEvent}
 * 的说明——消息体小、无版本耦合，且回源时拿到的一定是最新值，重复/乱序消费天然幂等。
 * <p>
 * 幂等性：upsert 以实体ID为 ES 文档 _id，重复消费结果一致；delete 对不存在的文档视为成功。
 * <p>
 * 降级：回源失败时**不删除**索引文档（宁可暂时陈旧，也不因一次抖动把用户列表清空），
 * 仅记录错误日志，并可通过 {@link #rebuildAll()} 全量重建兜底。
 */
@Service
public class ListIndexSyncService {

    private static final Logger log = LoggerFactory.getLogger(ListIndexSyncService.class);

    /** 全量重建用户索引时的分批大小 */
    private static final int USER_REBUILD_BATCH_SIZE = 500;

    @Autowired
    private OrderSourceClient orderSourceClient;
    @Autowired
    private ReviewSourceClient reviewSourceClient;
    @Autowired
    private UserSourceClient userSourceClient;

    @Autowired
    private OrderDocumentRepository orderRepository;
    @Autowired
    private ReviewDocumentRepository reviewRepository;
    @Autowired
    private UserDocumentRepository userRepository;

    // ==================== 订单索引 ====================

    /**
     * 同步单个订单到索引（创建 / 状态流转 / 退款等所有变更统一走这里）
     * <p>
     * 说明：订单的创建、支付、发货、收货、完成、取消、退款都会改变列表展示
     * （状态、金额、是否已评价），因此统一按「取最新值 upsert」处理。
     */
    public void syncOrder(Long orderId) {
        try {
            OrderIndexItem item = fetch(orderId, orderSourceClient::getIndexItem, "订单");
            if (item == null) {
                // 回源拿不到（订单已删除或订单服务异常）：保守起见仅记录，不擅自删索引
                log.warn("订单索引回源无数据，跳过同步: orderId={}", orderId);
                return;
            }
            orderRepository.save(toOrderDocument(item));
            log.info("订单索引已同步: orderId={}, status={}", orderId, item.getStatus());
        } catch (Exception e) {
            log.error("同步订单索引失败, orderId={}", orderId, e);
        }
    }

    /** 从索引删除订单 */
    public void deleteOrder(Long orderId) {
        try {
            orderRepository.deleteById(String.valueOf(orderId));
            log.info("订单索引已删除: orderId={}", orderId);
        } catch (Exception e) {
            log.error("删除订单索引失败, orderId={}", orderId, e);
        }
    }

    // ==================== 评价索引 ====================

    /**
     * 同步单个评价到索引（新增 / 修改）
     */
    public void syncReview(Long reviewId) {
        try {
            ReviewIndexItem item = fetch(reviewId, reviewSourceClient::getIndexItem, "评价");
            if (item == null) {
                log.warn("评价索引回源无数据，跳过同步: reviewId={}", reviewId);
                return;
            }
            reviewRepository.save(toReviewDocument(item));
            log.info("评价索引已同步: reviewId={}, productId={}", reviewId, item.getProductId());
        } catch (Exception e) {
            log.error("同步评价索引失败, reviewId={}", reviewId, e);
        }
    }

    /** 从索引删除评价 */
    public void deleteReview(Long reviewId) {
        try {
            reviewRepository.deleteById(String.valueOf(reviewId));
            log.info("评价索引已删除: reviewId={}", reviewId);
        } catch (Exception e) {
            log.error("删除评价索引失败, reviewId={}", reviewId, e);
        }
    }

    // ==================== 用户索引 ====================

    /**
     * 同步单个用户到索引（注册 / 状态变更 / 资料变更 / 销号前）
     */
    public void syncUser(Long userId) {
        try {
            UserIndexItem item = fetch(userId, userSourceClient::getIndexItem, "用户");
            if (item == null) {
                log.warn("用户索引回源无数据，跳过同步: userId={}", userId);
                return;
            }
            userRepository.save(toUserDocument(item));
            log.info("用户索引已同步: userId={}, role={}, status={}", userId, item.getRole(), item.getStatus());
        } catch (Exception e) {
            log.error("同步用户索引失败, userId={}", userId, e);
        }
    }

    /** 从索引删除用户（销号） */
    public void deleteUser(Long userId) {
        try {
            userRepository.deleteById(String.valueOf(userId));
            log.info("用户索引已删除: userId={}", userId);
        } catch (Exception e) {
            log.error("删除用户索引失败, userId={}", userId, e);
        }
    }

    // ==================== 全量重建 ====================

    /**
     * 全量重建 order / review / user 三类索引
     * <p>
     * 用途：MQ 消息丢失、索引结构变更或首次上线后的兜底。商品/商家两类索引的
     * 全量重建由 {@link SearchSyncService#rebuildAll()} 负责（历史实现）。
     *
     * @return 重建条数：order / review / user
     */
    public int[] rebuildAll() {
        long start = System.currentTimeMillis();
        int orderCount = rebuildOrders();
        int reviewCount = rebuildReviews();
        int userCount = rebuildUsers();
        log.info("列表索引全量重建完成：订单 {} 条，评价 {} 条，用户 {} 条，耗时 {} ms",
                orderCount, reviewCount, userCount, System.currentTimeMillis() - start);
        return new int[]{orderCount, reviewCount, userCount};
    }

    /**
     * 全量重建订单索引
     * <p>
     * <b>拉取失败时必须放弃重建，不能用空数据覆盖索引</b>：
     * 原实现即使拿到非 200（或异常被吞）也会继续执行 {@code deleteAll()}，
     * 把订单索引清空。虽然当前没有 fallback 时异常会直接进 catch 而「侥幸」安全，
     * 但只要以后为 orderSourceClient 加了降级（返回 Result 而非抛异常），
     * 这条路径就会变成「静默清空索引」。这里改为显式校验、失败即返回。
     */
    private int rebuildOrders() {
        Result<List<OrderIndexItem>> r;
        try {
            r = orderSourceClient.listAllIndexItems();
        } catch (Exception e) {
            log.error("全量重建订单索引中止：拉取源数据异常，已保留原有索引", e);
            return 0;
        }
        if (r == null || r.getCode() == null || r.getCode() != 200 || r.getData() == null) {
            log.error("全量重建订单索引中止：拉取源数据失败，已保留原有索引。resp={}",
                    r == null ? null : r.getMessage());
            return 0;
        }

        List<OrderDocument> docs = new ArrayList<>();
        for (OrderIndexItem i : r.getData()) {
            if (i != null && i.getId() != null) {
                docs.add(toOrderDocument(i));
            }
        }
        orderRepository.deleteAll();
        orderRepository.saveAll(docs);
        return docs.size();
    }

    /**
     * 全量重建评价索引
     * <p>
     * 失败处理同 {@link #rebuildOrders}：拉取不到就保留旧索引，绝不用空数据覆盖。
     */
    private int rebuildReviews() {
        Result<List<ReviewIndexItem>> r;
        try {
            r = reviewSourceClient.listAllIndexItems();
        } catch (Exception e) {
            log.error("全量重建评价索引中止：拉取源数据异常，已保留原有索引", e);
            return 0;
        }
        if (r == null || r.getCode() == null || r.getCode() != 200 || r.getData() == null) {
            log.error("全量重建评价索引中止：拉取源数据失败，已保留原有索引。resp={}",
                    r == null ? null : r.getMessage());
            return 0;
        }

        List<ReviewDocument> docs = new ArrayList<>();
        for (ReviewIndexItem i : r.getData()) {
            if (i != null && i.getId() != null) {
                docs.add(toReviewDocument(i));
            }
        }
        reviewRepository.deleteAll();
        reviewRepository.saveAll(docs);
        return docs.size();
    }

    /**
     * 全量重建用户索引（分批拉取，避免单次返回过多数据）
     * <p>
     * <b>关键：拉取失败时必须「放弃重建」而不是「用残缺数据重建」</b>。
     * 本方法原本在所有 break 之后都会执行 {@code userRepository.deleteAll()}，
     * 于是只要有**任意一页**拉取失败（含降级返回的错误），就会把 user 索引清空、
     * 再写入前面几页的不完整数据 —— 用户列表大面积丢失，而且看起来「重建成功了」。
     * <p>
     * 因此这里用 {@code fetchFailed} 标记区分两种情况：
     * <ul>
     *   <li>正常翻完（最后一页不满一页）→ 正常重建；</li>
     *   <li>中途拉取失败 → 直接返回 0，<b>保留旧索引</b>，由调用方/日志感知失败。</li>
     * </ul>
     */
    private int rebuildUsers() {
        List<UserDocument> all = new ArrayList<>();
        try {
            int page = 1;
            while (true) {
                Result<List<UserIndexItem>> r = userSourceClient.listIndexItems(page, USER_REBUILD_BATCH_SIZE);

                // 拉取失败（服务不可用、超时、或降级返回错误）→ 放弃本次重建，保留旧索引
                if (r == null || r.getCode() == null || r.getCode() != 200 || r.getData() == null) {
                    log.error("全量重建用户索引中止：第 {} 页拉取失败，已保留原有索引（不做删改）。resp={}",
                            page, r == null ? null : r.getMessage());
                    return 0;
                }
                // 空页 = 已翻到末尾，正常结束
                if (r.getData().isEmpty()) {
                    break;
                }
                for (UserIndexItem i : r.getData()) {
                    if (i != null && i.getId() != null) {
                        all.add(toUserDocument(i));
                    }
                }
                if (r.getData().size() < USER_REBUILD_BATCH_SIZE) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            log.error("全量重建用户索引中止：拉取过程异常，已保留原有索引（不做删改）", e);
            return 0;
        }

        // 只有「完整拉取成功」才动索引
        userRepository.deleteAll();
        userRepository.saveAll(all);
        return all.size();
    }

    // ==================== 转换（与 SearchInternalController 保持一致） ====================

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

    /**
     * 回源取索引视图的通用封装
     * <p>
     * 把「Feign 调用 + 校验 code + 取 data」收敛到一处，避免三类索引各写一遍。
     *
     * @param id           实体ID（为 null 直接返回 null）
     * @param fetcher      具体回源调用
     * @param entityName   实体中文名（仅用于日志）
     */
    private <T> T fetch(Long id, java.util.function.Function<Long, Result<T>> fetcher, String entityName) {
        if (id == null) {
            return null;
        }
        Result<T> r = fetcher.apply(id);
        if (r == null || r.getCode() == null || r.getCode() != 200) {
            log.warn("{}索引回源失败: id={}, resp={}", entityName, id, r == null ? null : r.getMessage());
            return null;
        }
        return r.getData();
    }
}
