package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.product.ProductBrief;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class ProductInternalClientFallback implements FallbackFactory<ProductInternalClient> {
    private static final Logger log = LoggerFactory.getLogger(ProductInternalClientFallback.class);

    @Override
    public ProductInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <ProductInternalClientFallback-product> 进入降级, reason={}", reason, cause);
        }
        return new ProductInternalClient() {
            @Override
            public Result<List<ProductBrief>> listProductBriefs(String ids) {
                log.warn("商户批量获取商品名失败，方法：listProductBriefs(String ids)，productIds={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
