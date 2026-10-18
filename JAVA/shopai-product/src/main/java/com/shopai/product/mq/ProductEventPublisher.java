package com.shopai.product.mq;

import com.shopai.common.mq.IndexChangedEvent;
import com.shopai.common.mq.IndexEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 商品变更事件发布器（MQ 生产者）
 * <p>
 * 微服务拆分说明（列表查询改造）：商品列表原先走 Redis 缓存
 * （{@code cache:product:list:{hash}}、{@code cache:product:hot:{limit}}），
 * 任意商品变更就 {@code deleteByPrefix(CACHE_PRODUCT_LIST)} 把全部页码的缓存清空。
 * 改造后商品列表由 shopai-search 的 product 索引承载，本发布器负责在商品增删改后
 * 通知搜索服务同步索引。
 * <p>
 * 实现说明：原先本类自己拼 {@code ProductChangedEvent} + 指定路由键，
 * 现在统一收敛到公共库的 {@link IndexEventPublisher}（五类实体共用一套投递逻辑、
 * 消息头与异常兜底），本类只负责把商品语义翻译成「实体类型 + 操作」。
 */
@Component
public class ProductEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductEventPublisher.class);

    @Autowired
    private IndexEventPublisher indexEventPublisher;

    /**
     * 发布商品变更事件
     *
     * @param operation 变更类型：create / update / delete
     * @param productId 商品ID
     */
    public void publish(String operation, Long productId) {
        if (productId == null) {
            log.warn("商品ID为空，跳过索引事件发布, operation={}", operation);
            return;
        }
        if ("delete".equalsIgnoreCase(operation)) {
            indexEventPublisher.publishDelete(IndexChangedEvent.EntityType.PRODUCT, productId);
        } else {
            // create / update 在索引侧统一按「取最新值 upsert」处理
            indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.PRODUCT, productId);
        }
    }
}
