package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.index.UserIndexItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 Feign 客户端（搜索服务侧，user 索引回源）
 * <p>
 * 微服务拆分说明（列表查询改造）：搜索服务消费 {@code user.changed} 事件后，
 * 通过本接口回源取「用户索引视图」（含客户昵称、店铺名等冗余检索字段，
 * 由用户服务装配；**不含任何凭据字段**）。
 * <p>
 * 安全说明：{@code /api/user/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_USER,
        path = InternalApiPaths.USER_INTERNAL,
        contextId = "search-user",
        fallbackFactory = UserSourceClientFallback.class
)
public interface UserSourceClient {

    /**
     * 取单个用户的索引视图
     *
     * @param userId 用户ID
     * @return 索引视图；用户不存在时 data 为 null
     */
    @GetMapping("/index/user/{userId}")
    Result<UserIndexItem> getIndexItem(@PathVariable("userId") Long userId);

    /**
     * 分页取用户索引视图（全量重建用，避免一次拉取过多）
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    @GetMapping("/index/users")
    Result<List<UserIndexItem>> listIndexItems(@RequestParam("page") int page,
                                               @RequestParam("size") int size);
}
