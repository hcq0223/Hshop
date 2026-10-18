package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantIndexItem;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商家服务 Feign 降级实现（搜索服务侧）
 * <p>
 * <b>同样必须「失败」而不是「空数据」</b>：搜索服务把「源端返回空」理解为
 * 「该商家不存在或未认证 → 从索引删除」。若降级返回空列表，
 * 商家服务抖动期间会把已认证商家从搜索结果里静默移除。
 * <p>
 * 因此统一返回 <b>非 200 的 Result</b>，由调用方按「拉取失败」跳过同步，
 * 保持索引原样；数据一致性交给 MQ 重试 + 死信 + 全量重建兜底。
 */
@Component
public class MerchantInternalClientFallback implements FallbackFactory<MerchantInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(MerchantInternalClientFallback.class);

    @Override
    public MerchantInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <MerchantInternalClient-search> 进入降级, reason={}", reason, cause);
        }
        return new MerchantInternalClient() {

            @Override
            public Result<List<MerchantIndexItem>> listAllForIndex() {
                log.error("商家服务暂不可用, 全量索引重建无法拉取源数据（本次重建将得到空索引，请稍后重试）, reason={}",
                        reason);
                return Result.error("商家服务暂不可用，无法拉取商家索引源数据");
            }

            @Override
            public Result<List<MerchantIndexItem>> listByIdsForIndex(String ids) {
                // 必须返回非 200：返回空列表会被理解为「商家已删除/未认证」→ 误删索引
                log.error("商家服务暂不可用, 商家索引回源失败（跳过同步，保持索引原样）, ids={}, reason={}",
                        ids, reason);
                return Result.error("商家服务暂不可用，商家索引回源失败");
            }
        };
    }
}
