package com.shopai.seckill.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 商家服务 Feign 降级实现（秒杀服务侧）
 * <p>
 * 返回非 200（失败），让调用方抛「无法确认商家身份」而不是当成「不是商家」。
 * <p>
 * <b>为什么不能返回 data=null 表示「不是商家」</b>：
 * 商家服务抖动时，所有商家登录后台都会收到「您还不是商家，请先入驻」，
 * 这会引发大量无意义的客服工单。而失败语义下前端提示「服务暂时不可用，请重试」，
 * 用户知道稍后再试即可，不会误以为自己的商家身份丢了。
 */
@Component
public class MerchantInternalClientFallback implements FallbackFactory<MerchantInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(MerchantInternalClientFallback.class);

    @Override
    public MerchantInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <MerchantInternalClient-seckill> 进入降级, reason={}", reason, cause);
        }
        return userId -> {
            log.error("商家服务暂不可用, 无法解析商家身份, userId={}, reason={}", userId, reason);
            return Result.error("商家服务暂不可用，无法确认商家身份");
        };
    }
}
