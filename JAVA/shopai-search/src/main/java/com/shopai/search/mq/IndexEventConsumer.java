package com.shopai.search.mq;

import com.shopai.common.mq.IndexChangedEvent;
import com.shopai.search.config.RabbitMqConfig;
import com.shopai.search.service.ListIndexSyncService;
import com.shopai.search.service.SearchSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 索引变更事件消费者（搜索服务）：消费五类变更事件，增量同步对应 ES 索引
 * <p>
 * 微服务拆分说明（列表查询改造）：改造前只有 product / merchant 两类事件，
 * 且事件体是各自的 {@code XxxChangedEvent}。现在五类实体统一用公共库的
 * {@link IndexChangedEvent}（entityType + operation + id），因此消费端可以用
 * 同一套分发逻辑处理，避免每加一类索引就新增一个消费者方法。
 * <p>
 * 分发规则：
 * <ul>
 *   <li>{@code operation=delete} → 从索引删除该文档；</li>
 *   <li>其他（upsert）→ 回源取最新值写入索引（见 {@link ListIndexSyncService}）；</li>
 *   <li>商品/商家两类沿用既有 {@link SearchSyncService}（回源逻辑一致，已稳定运行）。</li>
 * </ul>
 * <p>
 * 可靠性：由 {@code application.yml} 的 listener 配置承担——自动 ack、失败重试 3 次、
 * {@code default-requeue-rejected=false} 使最终失败的消息进死信队列而非无限重回队列。
 * 全量重建作为最终一致性兜底：商品/商家走 {@code POST /api/search/rebuild}，
 * 订单/评价/用户走 {@link ListIndexSyncService#rebuildAll()}。
 * <p>
 * 幂等性：所有同步都按「实体ID → 最新值 upsert」或「按ID删除」执行，
 * 重复、乱序消费的结果都收敛到同一状态。
 */
@Component
public class IndexEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexEventConsumer.class);

    @Autowired
    private SearchSyncService searchSyncService;

    @Autowired
    private ListIndexSyncService listIndexSyncService;

    // ==================== 商品 ====================

    /**
     * 消费商品变更事件 → 同步 product 索引
     */
    @RabbitListener(queues = RabbitMqConfig.PRODUCT_CHANGED_QUEUE)
    public void onProductChanged(IndexChangedEvent event) {
        Long id = idOf(event, "商品");
        if (id == null) {
            return;
        }
        if (event.isDelete()) {
            searchSyncService.deleteProduct(id);
        } else {
            searchSyncService.syncProduct(id);
        }
    }

    // ==================== 商家 ====================

    /**
     * 消费商家变更事件 → 同步 merchant 索引
     */
    @RabbitListener(queues = RabbitMqConfig.MERCHANT_CHANGED_QUEUE)
    public void onMerchantChanged(IndexChangedEvent event) {
        Long id = idOf(event, "商家");
        if (id == null) {
            return;
        }
        if (event.isDelete()) {
            searchSyncService.deleteMerchant(id);
        } else {
            // 未认证商家会被同步逻辑自动从索引删除（保持搜索仅返回可用商家）
            searchSyncService.syncMerchant(id);
        }
    }

    // ==================== 订单 ====================

    /**
     * 消费订单变更事件 → 同步 order 索引
     * <p>
     * 覆盖订单的全生命周期：创建、支付、发货、收货、完成、取消、退款。
     * 这些都会改变列表展示（状态/金额/是否已评价），故统一按最新值 upsert。
     */
    @RabbitListener(queues = RabbitMqConfig.ORDER_CHANGED_QUEUE)
    public void onOrderChanged(IndexChangedEvent event) {
        Long id = idOf(event, "订单");
        if (id == null) {
            return;
        }
        if (event.isDelete()) {
            listIndexSyncService.deleteOrder(id);
        } else {
            listIndexSyncService.syncOrder(id);
        }
    }

    // ==================== 评价 ====================

    /**
     * 消费评价变更事件 → 同步 review 索引
     */
    @RabbitListener(queues = RabbitMqConfig.REVIEW_CHANGED_QUEUE)
    public void onReviewChanged(IndexChangedEvent event) {
        Long id = idOf(event, "评价");
        if (id == null) {
            return;
        }
        if (event.isDelete()) {
            listIndexSyncService.deleteReview(id);
        } else {
            listIndexSyncService.syncReview(id);
        }
    }

    // ==================== 用户 ====================

    /**
     * 消费用户变更事件 → 同步 user 索引
     * <p>
     * 覆盖注册、账号状态变更（启用/禁用）、资料变更（用户名/手机/邮箱）、销号。
     * 说明：用户索引不含凭据字段，见 {@link com.shopai.search.document.UserDocument}。
     */
    @RabbitListener(queues = RabbitMqConfig.USER_CHANGED_QUEUE)
    public void onUserChanged(IndexChangedEvent event) {
        Long id = idOf(event, "用户");
        if (id == null) {
            return;
        }
        if (event.isDelete()) {
            listIndexSyncService.deleteUser(id);
        } else {
            listIndexSyncService.syncUser(id);
        }
    }

    // ==================== 工具 ====================

    /**
     * 校验事件并解析出实体ID
     * <p>
     * 事件体里的 id 用字符串承载（避免雪花ID在 JSON 中的精度问题），此处解析回 Long。
     * 解析失败按「无法处理」处理：记 warn 并返回 null（消息被 ack 丢弃，不会无限重试）。
     */
    private Long idOf(IndexChangedEvent event, String entityName) {
        if (event == null || event.getId() == null || event.getId().isBlank()) {
            log.warn("收到空的{}变更事件，忽略", entityName);
            return null;
        }
        try {
            return Long.valueOf(event.getId().trim());
        } catch (NumberFormatException e) {
            log.warn("{}变更事件ID非法，忽略: id={}", entityName, event.getId());
            return null;
        }
    }
}
