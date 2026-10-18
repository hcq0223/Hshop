package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.OrderInfo;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderInternalClientFallback implements FallbackFactory<OrderInternalClient> {
    private static final Logger log = LoggerFactory.getLogger(OrderInternalClientFallback.class);
    @Override
    public OrderInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <OrderInternalClientFallback-merchant> 进入降级, reason={}", reason, cause);
        }
        return new OrderInternalClient() {
            @Override
            public Result<OrderInfo> getOrder(Long orderId) {
                log.warn("商户查询订单服务暂时不可用, 方法:getOrder(Long orderId), orderId={}, reason={}", orderId, reason);
                return null;
            }

            @Override
            public Result<Integer> markReviewed(Long orderId) {
                log.warn("订单服务暂时不可用, 标记订单已评价失败, orderId={}, reason={}", orderId, reason);
                return Result.error("订单服务暂时不可用, 标记订单已评价失败");
            }

            @Override
            public Result<Integer> countCompletedOrders(Long merchantId, String statuses, String start, String end) {
                log.warn("订单服务暂时不可用, 统计周完成订单数失败, merchantId={}, reason={}", merchantId, reason);
                return Result.ok(0);
            }
        };
    }
}
