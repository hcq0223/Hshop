package com.shopai.product.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.StockItem;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 库存服务 Feign 降级实现
 * <p>
 * 微服务拆分说明：库存服务不可用时，批量查库存降级为空列表，
 * 商品详情页不展示库存（避免跨服务雪崩），符合设计文档「错误处理与降级」要求。
 */
@Component
public class InventoryClientFallback implements FallbackFactory<InventoryClient> {
    private static final Logger log = LoggerFactory.getLogger(InventoryClientFallback.class);

    @Override
    public InventoryClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <InventoryClient-product> 进入降级, reason={}", reason, cause);
        }
        return new InventoryClient() {
            @Override
            public List<StockItem> batchGetStock(String skuIds) {
                log.warn("库存服务不可用，批量查询库存降级为空列表, skuIds={},reason={}", skuIds, reason);
                return Collections.emptyList();
            }

            @Override
            public Result<?> init(Long skuId, int initStock) {
                return null;
            }
        };
    }


}
