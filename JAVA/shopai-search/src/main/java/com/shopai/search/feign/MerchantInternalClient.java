package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.MerchantIndexItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商家服务 Feign 客户端（搜索服务侧）
 * <p>
 * 微服务拆分说明：搜索服务原先通过自己的 {@code MerchantRefMapper} 跨库直查 merchants，
 * 并 JOIN users 取创建时间、按账号状态过滤。按「独立库」处理后 merchants 归属
 * shopai-merchant 服务，商家服务负责在本地查询、Feign 调用户服务校验账号状态，
 * 搜索服务只负责「拉取 + 写 ES」，故改为本 Feign 客户端。
 * <p>
 * 安全说明：{@code /api/merchant/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_MERCHANT,
        path = InternalApiPaths.MERCHANT_INTERNAL,
        contextId = "search-merchant",
        fallbackFactory = MerchantInternalClientFallback.class
)
public interface MerchantInternalClient {

    /**
     * 全量商家索引条目（替代原 MerchantRefMapper.selectAllForIndex）
     * <p>
     * 供搜索服务全量重建 merchant 索引使用。
     * 商家服务侧已按「已认证 + 账号正常」过滤（等价原 {@code INNER JOIN users} 语义）。
     */
    @GetMapping("/index/merchants")
    Result<List<MerchantIndexItem>> listAllForIndex();

    /**
     * 按商家ID批量获取索引条目（替代原 MerchantRefMapper.selectOneForIndex）
     * <p>
     * 供搜索服务 MQ 增量同步使用。返回空列表表示该商家不存在或未认证/账号不可用，
     * 搜索服务据此从索引中删除对应文档。
     *
     * @param ids 逗号分隔的商家ID列表
     */
    @GetMapping("/index/merchant")
    Result<List<MerchantIndexItem>> listByIdsForIndex(@RequestParam("ids") String ids);
}
