package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.index.ReviewIndexItem;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商家服务 Feign 降级实现（搜索服务侧，review 索引回源）
 * <p>
 * <b>降级返回非 200 的 Result（表示「回源失败」，而非「评价不存在」）</b>，
 * 语义与理由同 {@link OrderSourceClientFallback}：
 * 避免「失败」退化成「不存在」，被调用方误判为可从索引删除。
 */
@Component
public class ReviewSourceClientFallback implements FallbackFactory<ReviewSourceClient> {

    private static final Logger log = LoggerFactory.getLogger(ReviewSourceClientFallback.class);

    @Override
    public ReviewSourceClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <ReviewSourceClient-search> 进入降级, reason={}", reason, cause);
        }
        return new ReviewSourceClient() {

            @Override
            public Result<ReviewIndexItem> getIndexItem(Long reviewId) {
                log.error("商家服务暂不可用, 评价索引回源失败（跳过同步，保持索引原样）, reviewId={}, reason={}",
                        reviewId, reason);
                return Result.error("商家服务暂不可用，评价索引回源失败");
            }

            @Override
            public Result<List<ReviewIndexItem>> listAllIndexItems() {
                log.error("商家服务暂不可用, 全量重建无法拉取评价源数据（请稍后重试）, reason={}", reason);
                return Result.error("商家服务暂不可用，无法拉取评价索引源数据");
            }
        };
    }
}
