package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 秒杀服务 Feign 降级实现（订单服务侧）
 * <p>
 * <b>降级必须返回失败，且调用方必须吞掉这个失败。</b>
 * <p>
 * 这个回调是「通知」而不是「命令」：订单已经创建成功了，秒杀侧的落账
 * 只是把状态同步过去。所以降级返回失败 → 调用方记日志但不抛异常 →
 * 订单创建照常提交；秒杀侧因为本地消息还停在 {@code sent} 会被重投，
 * 重投时订单服务按 orderNo 幂等返回同一笔订单，于是再次触发回调直到成功。
 * <p>
 * <b>反过来做会出大问题</b>：如果这里降级返回「成功」，秒杀侧会以为落账完成
 * 而把本地消息置为 done，那笔抢购记录就永远停在 pending 了 ——
 * 用户抢到了、订单也在，但秒杀侧查不到，表现为「我的秒杀订单」里看不到这一笔。
 */
@Component
public class SeckillInternalClientFallback implements FallbackFactory<SeckillInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(SeckillInternalClientFallback.class);

    @Override
    public SeckillInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <SeckillInternalClient-order> 进入降级, reason={}", reason, cause);
        }
        return result -> {
            log.error("秒杀服务暂不可用, 成单回调失败（订单已创建成功，秒杀侧将由消息重投兜底）, "
                            + "orderNo={}, orderId={}, reason={}",
                    result == null ? null : result.getOrderNo(),
                    result == null ? null : result.getOrderId(), reason);
            return Result.error("秒杀服务暂不可用，成单回调失败");
        };
    }
}
