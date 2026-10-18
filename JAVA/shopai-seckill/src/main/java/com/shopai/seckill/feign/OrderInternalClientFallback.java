package com.shopai.seckill.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.OrderInfo;
import com.shopai.common.dto.internal.seckill.SeckillOrderCreateRequest;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 订单服务 Feign 降级实现（秒杀服务侧）
 * <p>
 * <b>必须返回失败（非 200），而且必须是失败</b>：订单创建是秒杀链路的终点，
 * 只有拿到订单号才算这笔秒杀成功。
 * <ul>
 *   <li>如果降级返回「成功」或一个假订单号，秒杀侧会把抢购记录置为 success，
 *       用户看到「下单成功」却根本没有订单 —— 这是最严重的一类错误；</li>
 *   <li>返回失败时，调用方（消息重投任务）会按退避策略重试；
 *       重试耗尽后由补偿逻辑回补库存并如实告诉用户「下单失败，库存已退回」。</li>
 * </ul>
 * <p>
 * 换句话说：<b>宁可让用户明确知道失败了，也不能让他以为成功。</b>
 */
@Component
public class OrderInternalClientFallback implements FallbackFactory<OrderInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(OrderInternalClientFallback.class);

    @Override
    public OrderInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <OrderInternalClient-seckill> 进入降级, reason={}", reason, cause);
        }
        return new OrderInternalClient() {

            @Override
            public Result<SeckillOrderResult> createSeckillOrder(SeckillOrderCreateRequest req) {
                log.error("订单服务暂不可用, 秒杀下单失败（将由重投任务重试）, "
                                + "orderNo={}, customerId={}, skuId={}, reason={}",
                        req == null ? null : req.getOrderNo(),
                        req == null ? null : req.getCustomerId(),
                        req == null ? null : req.getSkuId(), reason);
                // 绝不能返回成功或伪造订单号
                return Result.error("订单服务暂不可用，秒杀下单失败");
            }

            @Override
            public Result<OrderInfo> getOrder(Long orderId) {
                log.error("订单服务暂不可用, 订单查询失败, orderId={}, reason={}", orderId, reason);
                return Result.error("订单服务暂不可用，无法查询订单");
            }
        };
    }
}
