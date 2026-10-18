package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductIndexItem;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品服务 Feign 降级实现（搜索服务侧）
 * <p>
 * <b>这里的降级必须「失败」而不是「空数据」，原因很关键</b>：
 * 搜索服务的索引同步逻辑把「源端返回空」理解为「该商品已不存在 → 从索引删除」。
 * 如果商品服务不可用时降级返回空列表，就会在商品服务抖动期间
 * <b>把大量在售商品从搜索索引里静默删掉</b>，用户搜不到任何东西，
 * 而且很难排查（数据库里商品好好的）。
 * <p>
 * 因此本降级统一返回 <b>非 200 的 Result</b>，让调用方按「拉取失败」处理：
 * <ul>
 *   <li>{@code SearchSyncService.loadProduct} → 返回 null → {@code syncProduct} 跳过同步，保持索引原样；</li>
 *   <li>{@code ListIndexSyncService} 的其它回源同理跳过。</li>
 * </ul>
 * 数据一致性由 MQ 重试、死信队列与「全量重建索引」兜底，
 * 而不是靠降级路径去猜数据是否存在。
 */
@Component
public class ProductInternalClientFallback implements FallbackFactory<ProductInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(ProductInternalClientFallback.class);

    @Override
    public ProductInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <ProductInternalClient-search> 进入降级, reason={}", reason, cause);
        }
        return new ProductInternalClient() {

            @Override
            public Result<List<ProductIndexItem>> listAllForIndex() {
                log.error("商品服务暂不可用, 全量索引重建无法拉取源数据（本次重建将得到空索引，请稍后重试）, reason={}",
                        reason);
                return Result.error("商品服务暂不可用，无法拉取商品索引源数据");
            }

            @Override
            public Result<List<ProductIndexItem>> listByIdsForIndex(String ids) {
                // 必须返回非 200：返回空列表会被理解为「商品已删除」→ 误删索引
                log.error("商品服务暂不可用, 商品索引回源失败（跳过同步，保持索引原样）, ids={}, reason={}",
                        ids, reason);
                return Result.error("商品服务暂不可用，商品索引回源失败");
            }
        };
    }
}
