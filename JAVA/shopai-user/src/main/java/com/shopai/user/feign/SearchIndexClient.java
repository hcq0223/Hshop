package com.shopai.user.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 搜索服务 Feign 客户端（用户服务侧）
 * <p>
 * 微服务拆分说明（列表查询改造）：后台用户列表原先走 Redis 缓存
 * {@code cache:user:list:{hash}} + {@code users LEFT JOIN customers/merchants} 联查，
 * 且任意用户变更即 {@code deleteByPrefix(CACHE_USER_LIST)} 把全部分页缓存清空。
 * 改造后用户列表由 shopai-search 的 user 索引承载，本客户端是查询入口。
 * <p>
 * 调用方保留自己原有的对外路由不变（见 AdminUserController），仅内部把数据来源换成 ES。
 * <p>
 * 安全说明：{@code /api/search/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_SEARCH,
        path = InternalApiPaths.SEARCH_INTERNAL,
        contextId = "user-search",
        fallbackFactory = SearchIndexClientFallback.class
)
public interface SearchIndexClient {

    /**
     * 统一列表查询（过滤 + 检索 + 排序 + 分页）
     *
     * @param query 查询请求（indexType 固定为 user）
     */
    @PostMapping("/list")
    Result<IndexListResult> list(@RequestBody IndexListQuery query);
}
