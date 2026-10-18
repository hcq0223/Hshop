package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 库存服务 Feign 降级实现（订单服务侧）
 * <p>
 * <b>本降级刻意「全部失败」而不是「安静跳过」</b>：
 * {@code deduct}（下单扣库存）与 {@code restore}（取消/退款回补库存）是订单状态机的
 * 分支事务参与者。若降级为「静默成功」：
 * <ul>
 *   <li>扣减失败被吞 → 订单照常创建但库存没扣 → <b>超卖</b>；</li>
 *   <li>回补失败被吞 → 订单已取消但库存没还回去 → <b>少卖</b>/库存对不上。</li>
 * </ul>
 * 因此这里返回非 200 的 Result，让 {@code OrderServiceImpl.deductStockViaFeign}
 * 按其既有逻辑抛异常，进而触发 {@code @GlobalTransactional} 全局回滚
 * （Seata 未启用时至少让本地事务回滚），把问题暴露出来而不是掩盖。
 * <p>
 * 对照说明：商品服务侧的 {@code InventoryClient}（只用于「批量查库存展示」）
 * 才是适合「降级为空列表」的场景 —— 读操作可以降级，写操作不行。
 */
@Component
public class InventoryClientFallback implements FallbackFactory<InventoryClient> {

    private static final Logger log = LoggerFactory.getLogger(InventoryClientFallback.class);

    @Override
    public InventoryClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <InventoryClient-order> 进入降级, reason={}", reason, cause);
        }
        return new InventoryClient() {

            @Override
            public Result<?> deduct(Long skuId, int qty) {
                log.error("库存服务暂不可用, 扣减库存失败（下单将回滚）, skuId={}, qty={}, reason={}",
                        skuId, qty, reason);
                return Result.error("库存服务暂不可用，扣减库存失败");
            }

            @Override
            public Result<?> restore(Long skuId, int qty) {
                log.error("库存服务暂不可用, 回补库存失败（取消/退款将回滚）, skuId={}, qty={}, reason={}",
                        skuId, qty, reason);
                return Result.error("库存服务暂不可用，回补库存失败");
            }
        };
    }
}
