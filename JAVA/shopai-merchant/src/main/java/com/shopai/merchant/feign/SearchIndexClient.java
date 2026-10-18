package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 搜索服务 Feign 客户端（商家服务侧）
 * <p>
 * 微服务拆分说明（列表查询改造）：商家分页列表与评价列表原先都走 Redis 缓存
 * （{@code cache:merchant:page:{hash}}、{@code cache:review:product:{id}}、
 * {@code cache:review:customer:{id}}），任意商家/评价变更即 {@code deleteByPrefix} 全清。
 * 改造后这两类列表由 shopai-search 的 merhant / review 索引承载，本客户端是查询入口。
 * <p>
 * 调用方保留自己原有的对外路由不变（见 MerchantController / ReviewController），
 * 仅内部把数据来源换成 ES。
 * <p>
 * 安全说明：{@code /api/search/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_SEARCH,
        path = InternalApiPaths.SEARCH_INTERNAL,
        contextId = "merchant-search",
        fallbackFactory = SearchIndexClientFallback.class

)
public interface SearchIndexClient {

    /**
     * 统一列表查询（过滤 + 检索 + 排序 + 分页）
     *
     * @param query 查询请求（indexType 为 merchant 或 review）
     */
    @PostMapping("/list")
    Result<IndexListResult> list(@RequestBody IndexListQuery query);
}
