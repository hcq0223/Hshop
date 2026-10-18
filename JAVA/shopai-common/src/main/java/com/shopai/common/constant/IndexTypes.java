package com.shopai.common.constant;

/**
 * 列表索引类型常量（共享库）
 * <p>
 * 微服务拆分说明（列表查询改造）：列表查询统一由 shopai-search 的 ES 索引承载，
 * 各业务服务在调用搜索服务时必须声明「查哪个索引」。此常量放在公共库，
 * 保证 {@code IndexListQuery.indexType}、{@code IndexChangedEvent.entityType}
 * 与搜索服务的索引类型判断三处取值一致。
 */
public final class IndexTypes {

    /** 商品索引（shopai-product 的数据） */
    public static final String PRODUCT = "product";

    /** 商家索引（shopai-merchant 的数据） */
    public static final String MERCHANT = "merchant";

    /** 订单索引（shopai-order 的数据） */
    public static final String ORDER = "order";

    /** 评价索引（shopai-merchant 的数据） */
    public static final String REVIEW = "review";

    /** 用户索引（shopai-user 的数据） */
    public static final String USER = "user";

    private IndexTypes() {
        // 工具类，禁止实例化
    }
}
