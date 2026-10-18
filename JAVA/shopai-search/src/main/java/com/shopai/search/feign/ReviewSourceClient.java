package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.index.ReviewIndexItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 商家服务 Feign 客户端（搜索服务侧，review 索引回源）
 * <p>
 * 微服务拆分说明（列表查询改造）：评价归 shopai-merchant 服务，搜索服务消费
 * {@code review.changed} 事件后，通过本接口回源取「评价索引视图」
 * （含评价人昵称/头像、商品名、店铺名，均由商家服务组装）。
 * <p>
 * 安全说明：{@code /api/merchant/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_MERCHANT,
        path = InternalApiPaths.MERCHANT_INTERNAL,
        contextId = "search-review",
        fallbackFactory = ReviewSourceClientFallback.class
)
public interface ReviewSourceClient {

    /**
     * 取单个评价的索引视图
     *
     * @param reviewId 评价ID
     * @return 索引视图；评价不存在时 data 为 null
     */
    @GetMapping("/index/review/{reviewId}")
    Result<ReviewIndexItem> getIndexItem(@PathVariable("reviewId") Long reviewId);

    /**
     * 取全量评价索引视图（全量重建用）
     */
    @GetMapping("/index/reviews")
    Result<List<ReviewIndexItem>> listAllIndexItems();
}
