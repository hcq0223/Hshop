package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 商品服务「累加销量」Feign 降级实现（订单服务侧）
 * <p>
 * <b>这里刻意「降级为成功」</b>，与 InventoryClient 的降级策略相反，理由：
 * {@code salesCount} 只是展示与热销榜排序用的**统计值**，不是财务/库存那种
 * 必须精确的数据；而下单主链路（扣库存、写订单、商户资金）都已成功。
 * 若因为「累加销量」失败就抛异常，会导致整笔下单回滚 ——
 * 为了一个统计字段牺牲真实成交，明显不划算。
 * <p>
 * 因此降级为「记录错误日志 + 返回成功」，让订单继续完成。
 * 后续可通过商品服务的索引全量重建/定时校准补正销量。
 */
@Component
public class ProductSalesClientFallback implements FallbackFactory<ProductSalesClient> {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesClientFallback.class);

    @Override
    public ProductSalesClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <ProductSalesClient-order> 进入降级, reason={}", reason, cause);
        }
        return (productId, delta) -> {
            log.error("商品服务暂不可用, 累加销量失败（不影响下单，销量后续可校准）, "
                    + "productId={}, delta={}, reason={}", productId, delta, reason);
            return Result.ok("销量累加已降级跳过");
        };
    }
}
