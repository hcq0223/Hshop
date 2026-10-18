package com.shopai.product.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.common.feign.FeignFallbackReason;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class MerchantInternalClientFallback implements FallbackFactory<MerchantInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(MerchantInternalClientFallback.class);
    @Override
    public MerchantInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <MerchantInternalClient-product> 进入降级, reason={}", reason, cause);
        }
        return new MerchantInternalClient() {
            @Override
            public Result<MerchantInfo> getByUserId(Long userId) {
                log.warn("商家服务暂时不可用,未找到关联的商家信息, userId={},reason={}", userId, reason);
                return null;
            }

            @Override
            public Result<List<MerchantBrief>> listBriefs(String ids) {
                log.warn("商家服务暂时不可用, 未找到商家列表信息, ids={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
