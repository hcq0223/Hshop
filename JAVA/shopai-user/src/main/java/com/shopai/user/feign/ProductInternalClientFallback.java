package com.shopai.user.feign;

import com.shopai.common.Result;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 商品服务 Feign 降级实现（用户服务侧）
 * <p>
 * <b>降级语义</b>：只用于管理员仪表盘的「商品总数」统计。商品服务不可用时
 * 降级为 0，仪表盘其余区块仍正常展示（避免跨服务雪崩）。
 * <p>
 * 用 {@code log.error} 记录，便于区分「确实没有商品」与「商品服务挂了」。
 */
@Component
public class ProductInternalClientFallback implements FallbackFactory<ProductInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(ProductInternalClientFallback.class);

    @Override
    public ProductInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <ProductInternalClient-user> 进入降级, reason={}", reason, cause);
        }
        return () -> {
            log.error("商品服务暂不可用, 仪表盘商品总数降级为 0, reason={}", reason);
            return Result.ok(0L);
        };
    }
}
