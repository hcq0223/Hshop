package com.shopai.merchant.mq;

import com.shopai.common.mq.IndexChangedEvent;
import com.shopai.common.mq.IndexEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 商家变更事件发布器（MQ 生产者）
 * <p>
 * 微服务拆分说明（列表查询改造）：商家列表原先走 Redis 缓存
 * （{@code cache:merchant:page:{hash}}），任意商家变更就
 * {@code deleteByPrefix(CACHE_MERCHANT_PAGE)} 把全部页码的缓存清空。
 * 改造后商家列表由 shopai-search 的 merchant 索引承载，本发布器负责在商家
 * 入驻/审核通过/店铺信息修改/评分变化后通知搜索服务同步索引。
 * <p>
 * 实现说明：原先本类自己拼 {@code MerchantChangedEvent} + 指定路由键，
 * 现在统一收敛到公共库的 {@link IndexEventPublisher}，本类只负责把商家语义
 * 翻译成「实体类型 + 操作」。
 */
@Component
public class MerchantEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MerchantEventPublisher.class);

    @Autowired
    private IndexEventPublisher indexEventPublisher;

    /**
     * 发布商家变更事件
     *
     * @param operation  变更类型：create / update / delete
     * @param merchantId 商家ID
     */
    public void publish(String operation, Long merchantId) {
        if (merchantId == null) {
            log.warn("商家ID为空，跳过索引事件发布, operation={}", operation);
            return;
        }
        if ("delete".equalsIgnoreCase(operation)) {
            indexEventPublisher.publishDelete(IndexChangedEvent.EntityType.MERCHANT, merchantId);
        } else {
            // create / update 在索引侧统一按「取最新值 upsert」处理
            // （未认证商家会被同步逻辑从索引移除，保证搜索只返回可用商家）
            indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.MERCHANT, merchantId);
        }
    }
}
