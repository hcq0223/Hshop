package com.shopai.search.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.index.OrderIndexItem;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单服务 Feign 降级实现（搜索服务侧，order 索引回源）
 * <p>
 * <b>降级返回非 200 的 Result（表示「回源失败」，而非「订单不存在」）</b>。
 * 理由：{@code ListIndexSyncService.syncOrder} 在 data 为 null 时只是
 * 「跳过同步、不删索引」，但把「失败」与「不存在」表达成同一种值终究有风险 ——
 * 后续若有人把那里改成「null 就删除」，降级路径就会变成静默删数据。
 * 显式用非 200 表达「失败」，语义不会随调用方重构而失真。
 * <p>
 * 全量重建接口同理：拉取失败时返回错误，避免把索引清空后写入空集合。
 */
@Component
public class OrderSourceClientFallback implements FallbackFactory<OrderSourceClient> {

    private static final Logger log = LoggerFactory.getLogger(OrderSourceClientFallback.class);

    @Override
    public OrderSourceClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <OrderSourceClient-search> 进入降级, reason={}", reason, cause);
        }
        return new OrderSourceClient() {

            @Override
            public Result<OrderIndexItem> getIndexItem(Long orderId) {
                log.error("订单服务暂不可用, 订单索引回源失败（跳过同步，保持索引原样）, orderId={}, reason={}",
                        orderId, reason);
                return Result.error("订单服务暂不可用，订单索引回源失败");
            }

            @Override
            public Result<List<OrderIndexItem>> listAllIndexItems() {
                log.error("订单服务暂不可用, 全量重建无法拉取订单源数据（请稍后重试）, reason={}", reason);
                return Result.error("订单服务暂不可用，无法拉取订单索引源数据");
            }
        };
    }
}
