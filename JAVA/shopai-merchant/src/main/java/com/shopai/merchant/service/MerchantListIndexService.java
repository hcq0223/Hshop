package com.shopai.merchant.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.common.Result;
import com.shopai.common.constant.IndexTypes;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.merchant.dto.MerchantIndexView;
import com.shopai.merchant.dto.ReviewIndexView;
import com.shopai.merchant.entity.CustomerRef;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.entity.Review;
import com.shopai.merchant.feign.SearchIndexClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家/评价列表查询服务（ES 版，替代原 Redis 列表缓存）
 * <p>
 * 微服务拆分说明（列表查询改造）：
 * 改造前这两类列表是「Redis 缓存 + 回源查库 + 全量失效」：
 * <pre>
 *   cache:merchant:page:{hash}  ← 任意商家变更 → deleteByPrefix(CACHE_MERCHANT_PAGE)
 *   cache:review:product:{id}   ← 任意评价增删 → deleteKey（按商品粒度）
 *   cache:review:customer:{id}  ← 任意评价增删 → deleteKey（按客户粒度）
 * </pre>
 * 其中商家分页列表的失效尤其粗：任何一家店铺改个名字，全部分页组合的缓存全废。
 * <p>
 * 改造后：列表直接查 shopai-search 的 merchant / review 索引。ES 支持任意条件组合的
 * 过滤、全文检索、排序与深分页，不再需要「缓存 + 失效」这一层。
 * <p>
 * 路由不变：本类只被商家服务内部调用，MerchantController / ReviewController 的
 * 对外路径与响应结构保持不变（评价的 {@code customer} 嵌套结构由本类装配还原）。
 * <p>
 * 降级策略：搜索服务不可用时返回空结果并记录错误日志；调用方据此返回空页而非 500。
 */
@Service
public class MerchantListIndexService {

    private static final Logger log = LoggerFactory.getLogger(MerchantListIndexService.class);

    @Autowired
    private SearchIndexClient searchIndexClient;
    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 商家列表 ====================

    /**
     * 商家分页列表（原 {@code merchantMapper.selectMerchantPage}）
     * <p>
     * 对应原实现：按店铺名关键字过滤，按 {@code rating DESC, id DESC} 排序。
     * 说明：原 SQL 还返回 {@code product_count}（跨库 JOIN products 统计），
     * 该字段在「独立库」改造中已移除（前端未使用）。
     *
     * @param page    页码（从 1 开始）
     * @param keyword 店铺名关键字（可选）
     * @return MyBatis-Plus 分页对象（保持原方法签名与返回类型不变）
     */
    public IPage<Merchant> queryMerchantPage(Page<Merchant> page, String keyword) {
        Map<String, String> filters = new HashMap<>();
        IndexListResult result = queryIndex(IndexTypes.MERCHANT, filters, keyword,
                "rating", "desc", (int) page.getCurrent(), (int) page.getSize());

        List<Merchant> records = toMerchants(result);

        // 组装与 MP 分页对象一致的结构，保证上层（缓存/返回）逻辑无需改动
        Page<Merchant> resultPage = new Page<>(page.getCurrent(), page.getSize(), result.getTotal());
        resultPage.setRecords(records);
        return resultPage;
    }

    /**
     * 商家公开列表（关键词 + 已认证过滤）
     * <p>
     * 说明：原顾客端 LIKE 搜索已迁至 {@code /api/search/merchant}（ES），
     * 本方法供服务内需要「按关键词取已认证商家」的场景复用。
     */
    public IPage<Merchant> queryVerifiedMerchantPage(Page<Merchant> page, String keyword) {
        Map<String, String> filters = new HashMap<>();
        filters.put("isVerified", "1");
        IndexListResult result = queryIndex(IndexTypes.MERCHANT, filters, keyword,
                "rating", "desc", (int) page.getCurrent(), (int) page.getSize());
        Page<Merchant> resultPage = new Page<>(page.getCurrent(), page.getSize(), result.getTotal());
        resultPage.setRecords(toMerchants(result));
        return resultPage;
    }

    // ==================== 评价列表 ====================

    /**
     * 按商品分页查评价（原 {@code reviewMapper.selectWithCustomerByProductId}）
     *
     * @param productId 商品ID
     * @param page      页码（从 1 开始）
     * @param size      每页条数
     * @return 评价列表（含 customer 嵌套对象），以及总数
     */
    public ReviewPage queryReviewsByProductId(Long productId, int page, int size) {
        Map<String, String> filters = new HashMap<>();
        filters.put("productId", String.valueOf(productId));
        IndexListResult result = queryIndex(IndexTypes.REVIEW, filters, null,
                "createTime", "desc", page, size);
        return new ReviewPage(toReviews(result), result.getTotal());
    }

    /**
     * 按客户查评价（原 {@code reviewMapper.selectByCustomerId}，无分页）
     *
     * @param customerId 客户ID
     */
    public List<Review> queryReviewsByCustomerId(Long customerId) {
        Map<String, String> filters = new HashMap<>();
        filters.put("customerId", String.valueOf(customerId));
        IndexListResult result = queryIndex(IndexTypes.REVIEW, filters, null,
                "createTime", "desc", 1, REVIEW_LIST_MAX_SIZE);
        return toReviews(result);
    }

    /**
     * 管理员分页查全部评价（原 {@code reviewMapper.selectPageWithDetails}）
     */
    public ReviewPage queryAllReviews(int page, int size) {
        IndexListResult result = queryIndex(IndexTypes.REVIEW, new HashMap<>(), null,
                "createTime", "desc", page, size);
        return new ReviewPage(toReviews(result), result.getTotal());
    }

    /** 评价列表一次最多返回条数（兼容无分页接口的语义） */
    private static final int REVIEW_LIST_MAX_SIZE = 200;

    // ==================== 统一查询 ====================

    private IndexListResult queryIndex(String indexType, Map<String, String> filters, String keyword,
                                       String sortField, String sortOrder, int page, int size) {
        IndexListQuery query = new IndexListQuery();
        query.setIndexType(indexType);
        query.setPage(page);
        query.setSize(size);
        query.setKeyword(keyword);
        query.setSortField(sortField);
        query.setSortOrder(sortOrder);
        query.setFilters(filters);
        try {
            Result<IndexListResult> r = searchIndexClient.list(query);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData();
            }
            log.warn("{}列表索引查询返回异常: code={}, msg={}", indexType,
                    r == null ? null : r.getCode(), r == null ? null : r.getMessage());
        } catch (Exception e) {
            log.error("调用搜索服务查询{}列表失败, filters={}, page={}", indexType, filters, page, e);
        }
        IndexListResult empty = new IndexListResult();
        empty.setRecords(Collections.emptyList());
        empty.setTotal(0);
        empty.setPage(page);
        empty.setSize(size);
        empty.setPages(0);
        return empty;
    }

    // ==================== 转换 ====================

    /**
     * 索引记录 → Merchant 列表
     * <p>
     * 单条转换失败跳过该条，不因一条脏数据导致整页失败。
     */
    private List<Merchant> toMerchants(IndexListResult result) {
        if (result == null || result.getRecords() == null) {
            return Collections.emptyList();
        }
        List<Merchant> list = new ArrayList<>(result.getRecords().size());
        for (Object record : result.getRecords()) {
            MerchantIndexView v = convert(record, MerchantIndexView.class);
            if (v == null) {
                continue;
            }
            Merchant m = new Merchant();
            m.setId(parseLong(v.getId()));
            m.setShopName(v.getShopName());
            m.setLogoUrl(v.getLogoUrl());
            m.setRating(v.getRating());
            m.setIsVerified(v.getIsVerified());
            m.setShopDescription(v.getShopDescription());
            list.add(m);
        }
        return list;
    }

    /**
     * 索引记录 → Review 列表
     * <p>
     * 关键点：把索引中平铺的 {@code customerNickname / customerAvatar} 装配回
     * 嵌套的 {@link CustomerRef} 对象，使前端仍可读 {@code r.customer.nickname} /
     * {@code r.customer.avatarUrl}，对外契约不变。
     */
    private List<Review> toReviews(IndexListResult result) {
        if (result == null || result.getRecords() == null) {
            return Collections.emptyList();
        }
        List<Review> list = new ArrayList<>(result.getRecords().size());
        for (Object record : result.getRecords()) {
            ReviewIndexView v = convert(record, ReviewIndexView.class);
            if (v == null) {
                continue;
            }
            Review r = new Review();
            r.setId(parseLong(v.getId()));
            r.setOrderId(parseLong(v.getOrderId()));
            r.setCustomerId(parseLong(v.getCustomerId()));
            r.setProductId(parseLong(v.getProductId()));
            r.setMerchantId(parseLong(v.getMerchantId()));
            r.setRating(v.getRating());
            r.setContent(v.getContent());
            r.setImages(v.getImages());
            r.setCreateTime(v.getCreateTime());
            r.setProductName(v.getProductName());
            r.setMerchantName(v.getMerchantName());

            // 还原嵌套 customer（原由 SQL LEFT JOIN customers 填充）
            if (v.getCustomerId() != null || v.getCustomerNickname() != null) {
                CustomerRef customer = new CustomerRef();
                customer.setId(parseLong(v.getCustomerId()));
                customer.setNickname(v.getCustomerNickname());
                customer.setAvatarUrl(v.getCustomerAvatar());
                r.setCustomer(customer);
            }
            list.add(r);
        }
        return list;
    }

    private <T> T convert(Object record, Class<T> type) {
        if (record == null) {
            return null;
        }
        if (type.isInstance(record)) {
            return type.cast(record);
        }
        try {
            return objectMapper.convertValue(record, type);
        } catch (Exception e) {
            log.error("索引记录转换为 {} 失败，已跳过该条: {}", type.getSimpleName(), record, e);
            return null;
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 评价分页结果（承载记录与总数）
     * <p>
     * 用一个小容器而非 Map，是为了让调用方（ReviewServiceImpl）拼装对外的
     * {@code {list, total, hasMore}} 结构时类型明确。
     */
    public static class ReviewPage {
        private final List<Review> list;
        private final long total;

        public ReviewPage(List<Review> list, long total) {
            this.list = list;
            this.total = total;
        }

        public List<Review> getList() {
            return list;
        }

        public long getTotal() {
            return total;
        }
    }
}
