package com.shopai.common.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 索引变更事件发布器（MQ 生产者，公共库）
 * <p>
 * 微服务拆分说明：列表查询改造后，商品 / 商家 / 订单 / 评价 / 用户 的**分页列表**由
 * shopai-search 的 ES 索引承载。各数据归属方在增删改后调用本发布器投递
 * {@link IndexChangedEvent}，搜索服务消费后回源拉取并增量同步 ES。
 * <p>
 * 为什么放在公共库：五个服务都要发同一套事件，放在公共库可保证路由键选择、消息头
 * （msgId 幂等键）、异常兜底策略完全一致；各服务只需注入本类，无需自己维护 RabbitTemplate 细节。
 * <p>
 * 可靠性：
 * - 消息持久化 + publisher-confirm（见各服务 application.yml）；
 * - 发送失败**不影响主业务事务**，仅记录错误日志——搜索侧另有「全量重建索引」兜底，
 *   且列表查询本身不参与写事务，不能因为 MQ 抖动而回滚用户的下单/上架等操作。
 */
@Component
public class IndexEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IndexEventPublisher.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发布「新增/修改」事件（统一按最新值 upsert）
     *
     * @param entityType 实体类型，见 {@link IndexChangedEvent.EntityType}
     * @param id         实体主键ID
     */
    public void publishUpsert(String entityType, Object id) {
        publish(IndexChangedEvent.upsert(entityType, id), routingKey(entityType, false));
    }

    /**
     * 发布「删除」事件
     *
     * @param entityType 实体类型，见 {@link IndexChangedEvent.EntityType}
     * @param id         实体主键ID
     */
    public void publishDelete(String entityType, Object id) {
        publish(IndexChangedEvent.delete(entityType, id), routingKey(entityType, true));
    }

    /**
     * 统一投递入口
     *
     * @param event      事件载荷
     * @param routingKey 路由键
     */
    private void publish(IndexChangedEvent event, String routingKey) {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.convertAndSend(MqConstants.DATA_EXCHANGE, routingKey, event, message -> {
                // msgId 用于消费端排查与去重；消息体本身已保证幂等
                message.getMessageProperties().setHeader("msgId", correlationData.getId());
                return message;
            }, correlationData);
            log.info("索引变更事件已发布, entityType={}, operation={}, id={}, routingKey={}, msgId={}",
                    event.getEntityType(), event.getOperation(), event.getId(), routingKey, correlationData.getId());
        } catch (Exception e) {
            // 发送失败不影响主流程，仅记录日志（搜索侧另有全量重建兜底）
            log.error("索引变更事件发布失败, entityType={}, operation={}, id={}, routingKey={}, msgId={}",
                    event.getEntityType(), event.getOperation(), event.getId(), routingKey, correlationData.getId(), e);
        }
    }

    /**
     * 实体类型 + 是否删除 → 路由键
     * <p>
     * 搜索服务按 {@code <entity>.changed.#} 通配绑定，因此 upsert 与 delete 会被同一队列接收。
     */
    private static String routingKey(String entityType, boolean delete) {
        String base;
        if (IndexChangedEvent.EntityType.PRODUCT.equals(entityType)) {
            base = MqConstants.ROUTING_PRODUCT_CHANGED;
        } else if (IndexChangedEvent.EntityType.MERCHANT.equals(entityType)) {
            base = MqConstants.ROUTING_MERCHANT_CHANGED;
        } else if (IndexChangedEvent.EntityType.ORDER.equals(entityType)) {
            base = MqConstants.ROUTING_ORDER_CHANGED;
        } else if (IndexChangedEvent.EntityType.REVIEW.equals(entityType)) {
            base = MqConstants.ROUTING_REVIEW_CHANGED;
        } else if (IndexChangedEvent.EntityType.USER.equals(entityType)) {
            base = MqConstants.ROUTING_USER_CHANGED;
        } else {
            throw new IllegalArgumentException("未知的实体类型: " + entityType);
        }
        return delete ? base + ".delete" : base;
    }
}
