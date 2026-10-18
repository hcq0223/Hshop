package com.shopai.seckill.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.CustomerIdPair;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户服务 Feign 降级实现（秒杀服务侧）
 * <p>
 * 返回空列表而不是报错，是<b>刻意</b>的选择：调用方
 * （{@code SeckillOrderService.resolveCustomerId}）把「映射不到 customerId」
 * 当作「本次抢购先记为 pending、稍后由定时任务重试解析」来处理，
 * 而不是直接判抢购失败。
 * <p>
 * 理由：用户服务抖动是暂时的，而用户明明抢到了却被判失败、库存被回补，
 * 对用户的伤害远大于「晚几分钟成单」。真正的兜底是超时任务 ——
 * 重试窗口内一直解析不出来，才回补库存并置失败。
 * <p>
 * 注意：这里与商品服务的降级策略相反（那边必须返回失败）。区别在于
 * 「降级返回空」在这两处的语义完全不同：那边空=商品不存在（会误删/误判），
 * 这里空=暂时查不到（可安全重试）。
 */
@Component
public class CustomerInternalClientFallback implements FallbackFactory<CustomerInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(CustomerInternalClientFallback.class);

    @Override
    public CustomerInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <CustomerInternalClient-seckill> 进入降级, reason={}", reason, cause);
        }
        return userIds -> {
            // 返回空列表 = 「暂时查不到映射」，交由定时任务重试，而不是判抢购失败
            log.warn("用户服务暂不可用, customerId 映射暂时失败（将稍后重试）, userIds={}, reason={}",
                    userIds, reason);
            return Result.ok(List.<CustomerIdPair>of());
        };
    }
}
