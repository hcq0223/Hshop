package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.index.UserIndexItem;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户服务 Feign 降级实现（搜索服务侧，user 索引回源）
 * <p>
 * <b>降级返回非 200 的 Result（表示「回源失败」，而非「用户不存在」）</b>，
 * 语义与理由同 {@link OrderSourceClientFallback}。
 * <p>
 * 特别是全量重建用的分页接口 {@code listIndexItems}：
 * {@code ListIndexSyncService.rebuildUsers} 用「返回空列表」作为**翻页终止条件**。
 * 若降级返回空列表，会把「用户服务挂了」误判成「已经翻到最后一页」，
 * 于是**用不完整的数据覆盖整个 user 索引**。返回非 200 可让重建流程提前失败，
 * 保留旧索引而不是写入残缺数据。
 */
@Component
public class UserSourceClientFallback implements FallbackFactory<UserSourceClient> {

    private static final Logger log = LoggerFactory.getLogger(UserSourceClientFallback.class);

    @Override
    public UserSourceClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <UserSourceClient-search> 进入降级, reason={}", reason, cause);
        }
        return new UserSourceClient() {

            @Override
            public Result<UserIndexItem> getIndexItem(Long userId) {
                log.error("用户服务暂不可用, 用户索引回源失败（跳过同步，保持索引原样）, userId={}, reason={}",
                        userId, reason);
                return Result.error("用户服务暂不可用，用户索引回源失败");
            }

            @Override
            public Result<List<UserIndexItem>> listIndexItems(int page, int size) {
                // 不能返回空列表：那会被 rebuildUsers 当成「已翻到最后一页」而提前结束重建
                log.error("用户服务暂不可用, 无法拉取用户索引源数据（重建将中止，保留旧索引）, "
                        + "page={}, size={}, reason={}", page, size, reason);
                return Result.error("用户服务暂不可用，无法拉取用户索引源数据");
            }
        };
    }
}
