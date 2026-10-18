package com.shopai.product.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 搜索服务 Feign 客户端（商品服务侧）
 * <p>
 * 微服务拆分说明（列表查询改造）：商品列表/热销榜原先走 Redis 缓存
 * （{@code cache:product:list:{hash}}、{@code cache:product:hot:{limit}}），
 * 任意商品变更即 {@code deleteByPrefix(CACHE_PRODUCT_LIST)} 把全部页码的缓存清空。
 * 改造后商品列表由 shopai-search 的 product 索引承载，本客户端是查询入口。
 * <p>
 * 调用方保留自己原有的对外路由不变（见 ProductController），仅内部把数据来源换成 ES。
 * <p>
 * 安全说明：{@code /api/search/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_SEARCH,
        path = InternalApiPaths.SEARCH_INTERNAL,
        contextId = "product-search",
        fallbackFactory = SearchIndexClientFallback.class

)
public interface SearchIndexClient {

    /**
     * 统一列表查询（过滤 + 检索 + 排序 + 分页）
     *
     * @param query 查询请求（indexType 固定为 product）
     */
    @PostMapping("/list")
    Result<IndexListResult> list(@RequestBody IndexListQuery query);

    /**
     * 批量写入/更新商品索引文档（分类改名后的扇出同步用）
     * <p>
     * 场景：product 索引里的 {@code categoryName / parentCategoryName} 是从 categories
     * 表冗余过去的，分类改名后这些字段会陈旧。此时按 categoryId 找出受影响商品，
     * 一次性把索引视图推给搜索服务批量 upsert —— 比逐条发 MQ 少 N 次消息投递 + N 次回源。
     *
     * @param items 商品索引视图列表（由商品服务组装好店铺名/分类名）
     * @return 写入条数
     */
    @PostMapping("/upsert/products")
    Result<Integer> upsertProducts(@RequestBody java.util.List<com.shopai.common.dto.internal.ProductIndexItem> items);
}
