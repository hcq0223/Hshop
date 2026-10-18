package com.shopai.order.service;

import com.shopai.common.Result;
import com.shopai.common.constant.IndexTypes;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.order.feign.SearchIndexClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单列表查询服务（ES 版，替代原 Redis 列表缓存）
 * <p>
 * 微服务拆分说明（列表查询改造）：
 * 改造前订单列表的实现是「Redis 缓存 + 回源查库」：
 * <pre>
 *   cacheKey = CACHE_ORDER_LIST + hash(status, customerId, merchantId, page, size)
 *   redisService.getOrSetTyped(cacheKey, ..., () -&gt; orderMapper.selectByStatus(...))
 * </pre>
 * 且任意订单变更都会 {@code deleteByPrefix(CACHE_ORDER_LIST)} —— 一次性清空
 * <b>所有用户、所有筛选组合、所有页码</b>的缓存。结果是：只要有人下单或改状态，
 * 全站订单列表缓存全废，缓存几乎只在流量低谷才有意义。
 * <p>
 * 改造后：列表直接查 shopai-search 的 order 索引。ES 本身支持任意条件组合的
 * 过滤、全文检索、排序与深分页，因此不再需要「缓存 + 全量失效」这一层。
 * <p>
 * 路由不变：本类只被订单服务内部调用，OrderController 的对外路径与响应结构保持不变。
 * <p>
 * 降级策略：搜索服务不可用时返回空列表并记录错误日志；调用方据此返回空页而非 500，
 * 避免搜索服务故障放大成订单服务不可用。
 */
@Service
public class OrderListIndexService {

    private static final Logger log = LoggerFactory.getLogger(OrderListIndexService.class);

    @Autowired
    private SearchIndexClient searchIndexClient;

    /**
     * 订单列表查询（顾客"我的订单" / 商家"店铺订单" / 管理员"全部订单"共用）
     * <p>
     * 对应原实现的三处查询：
     * - 顾客：{@code selectByCustomerId}（排除终态，分页）
     * - 商家：{@code selectByMerchantId}（排除终态）
     * - 管理员：{@code selectAllOrders}（排除终态，分页）
     * - 按状态：{@code selectByStatus}（精确状态，不排除）
     *
     * @param status           订单状态（可选，精确匹配）
     * @param customerId       客户ID（可选）
     * @param merchantId       商家ID（可选）
     * @param excludeFinalized 是否排除终态订单（completed/cancelled），
     *                         对应原 SQL {@code status NOT IN ('completed','cancelled')}
     * @param keyword          关键字（可选，匹配订单号/店铺名/客户昵称）
     * @param page             页码（从 1 开始）
     * @param size             每页条数
     */
    public IndexListResult query(String status, Long customerId, Long merchantId,
                                 boolean excludeFinalized, String keyword, int page, int size) {
        Map<String, String> filters = new HashMap<>();
        if (status != null && !status.isBlank()) {
            filters.put("status", status);
        }
        if (customerId != null) {
            filters.put("customerId", String.valueOf(customerId));
        }
        if (merchantId != null) {
            filters.put("merchantId", String.valueOf(merchantId));
        }
        if (excludeFinalized) {
            // 原 SQL：AND o.status NOT IN ('completed', 'cancelled')
            filters.put("excludeStatuses", "completed,cancelled");
        }
        return query(filters, keyword, page, size);
    }

    /**
     * 历史订单查询（已完成 + 已取消）
     * <p>
     * 对应原实现 {@code selectHistoryOrders(List.of("completed","cancelled"), ...)}，
     * 即「状态 IN 集合」，用 {@code statuses} 过滤键表达。
     */
    public IndexListResult queryHistory(Long customerId, Long merchantId, int page, int size) {
        Map<String, String> filters = new HashMap<>();
        filters.put("statuses", "completed,cancelled");
        if (customerId != null) {
            filters.put("customerId", String.valueOf(customerId));
        }
        if (merchantId != null) {
            filters.put("merchantId", String.valueOf(merchantId));
        }
        return query(filters, null, page, size);
    }

    /**
     * 统一查询入口：组装 IndexListQuery 并调搜索服务
     */
    private IndexListResult query(Map<String, String> filters, String keyword, int page, int size) {
        IndexListQuery query = new IndexListQuery();
        query.setIndexType(IndexTypes.ORDER);
        query.setPage(page);
        query.setSize(size);
        query.setKeyword(keyword);
        query.setSortField("createTime");
        query.setSortOrder("desc");
        query.setFilters(filters);

        try {
            Result<IndexListResult> r = searchIndexClient.list(query);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData();
            }
            log.warn("订单列表查询返回异常: code={}, msg={}",
                    r == null ? null : r.getCode(), r == null ? null : r.getMessage());
        } catch (Exception e) {
            log.error("调用搜索服务查询订单列表失败, filters={}, page={}", filters, page, e);
        }
        return emptyResult(page, size);
    }

    private IndexListResult emptyResult(int page, int size) {
        IndexListResult empty = new IndexListResult();
        empty.setRecords(List.of());
        empty.setTotal(0);
        empty.setPage(page);
        empty.setSize(size);
        empty.setPages(0);
        return empty;
    }
}
