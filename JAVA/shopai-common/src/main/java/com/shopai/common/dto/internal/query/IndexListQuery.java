package com.shopai.common.dto.internal.query;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用列表查询请求（跨服务契约）
 * <p>
 * 微服务拆分说明：列表查询改造后，列表由 shopai-search 的 ES 索引承载。
 * 各业务服务（商品/商家/订单/评价/用户）保留自己的对外路由不变，
 * 内部通过 Feign 调搜索服务的统一查询入口，请求体即本对象。
 * <p>
 * 设计说明：字段刻意保持「通用」而非每个列表一套 —— 搜索服务用同一个
 * {@code SearchListQueryService} 处理五类索引，只是按索引类型解释
 * {@code filters} 的键（如 product 索引支持 status/merchantId/categoryId，
 * order 索引支持 status/customerId/merchantId）。
 * 这样新增一个列表只需加一个索引 + 一组过滤器键，不用新增一套请求/响应类型。
 */
@Data
public class IndexListQuery implements Serializable {

    /**
     * 索引类型，见 {@code IndexType}（product / merchant / order / review / user）。
     */
    private String indexType;

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数 */
    private Integer size = 10;

    /**
     * 关键字全文检索。搜索服务按索引类型选择检索字段：
     * product → name/description/shopName；merchant → shopName/description；
     * order → orderNo/shopName/customerNickname；review → content/productName/shopName；
     * user → username/phone/email/customerNickname/shopName。
     */
    private String keyword;

    /**
     * 精确过滤条件（键值对）。null / 空串的条目会被忽略。
     * 常用键：
     * - 通用：{@code statuses}（逗号分隔，状态 IN 集合，如订单历史 "completed,cancelled"）、
     *   {@code excludeStatuses}（逗号分隔，排除某些状态，如订单列表排除 "completed,cancelled"）
     * - product：status、merchantId、categoryId
     * - merchant：isVerified
     * - order：status、statuses、customerId、merchantId、isReviewed
     * - review：productId、customerId、merchantId、rating
     * - user：role、status
     */
    private java.util.Map<String, String> filters;

    /**
     * 排序字段（ES 文档字段名，如 createTime / rating / salesCount / id）。
     * 为空时使用各索引的默认排序。
     */
    private String sortField;

    /** 排序方向：asc / desc（默认 desc）。由搜索服务做白名单校验，防止注入 */
    private String sortOrder;

    public int safePage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int safeSize() {
        if (size == null || size < 1) {
            return 10;
        }
        // 上限保护，避免一次拉取过多
        return Math.min(size, 200);
    }

    public String filter(String key) {
        if (filters == null) {
            return null;
        }
        String v = filters.get(key);
        return v == null || v.isBlank() ? null : v;
    }
}
