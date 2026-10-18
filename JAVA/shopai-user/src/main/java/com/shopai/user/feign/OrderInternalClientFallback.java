package com.shopai.user.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.order.DailyOrderStat;
import com.shopai.common.dto.internal.order.MerchantSalesStat;
import com.shopai.common.dto.internal.order.OrderStatusCount;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 订单服务 Feign 降级实现（用户服务侧）
 * <p>
 * <b>降级语义</b>：本客户端只服务**管理员仪表盘**的统计聚合，全部是只读查询。
 * 订单服务不可用时：
 * <ul>
 *   <li>{@code countOrders} → 0；{@code dailyOrderStats} / {@code countGroupByStatus} /
 *       {@code merchantSalesRanking} → 空列表；</li>
 *   <li>仪表盘上对应图表显示为空/0，其余区块（用户数、商品数、角色分布）仍正常 ——
 *       这正是「避免跨服务雪崩」的目标：一个依赖挂了不应让整个仪表盘 500。</li>
 * </ul>
 * 说明：这里刻意用 {@code log.error}（而非 debug）记录，便于运维从日志区分
 * 「确实没有订单」与「订单服务挂了导致全是 0」。
 */
@Component
public class OrderInternalClientFallback implements FallbackFactory<OrderInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(OrderInternalClientFallback.class);

    @Override
    public OrderInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <OrderInternalClient-user> 进入降级, reason={}", reason, cause);
        }
        return new OrderInternalClient() {

            @Override
            public Result<Long> countOrders() {
                log.error("订单服务暂不可用, 仪表盘订单总数降级为 0, reason={}", reason);
                return Result.ok(0L);
            }

            @Override
            public Result<List<DailyOrderStat>> dailyOrderStats(int days) {
                log.error("订单服务暂不可用, 仪表盘近{}天每日订单降级为空, reason={}", days, reason);
                return Result.ok(Collections.emptyList());
            }

            @Override
            public Result<List<OrderStatusCount>> countGroupByStatus() {
                log.error("订单服务暂不可用, 仪表盘订单状态分布降级为空, reason={}", reason);
                return Result.ok(Collections.emptyList());
            }

            @Override
            public Result<List<MerchantSalesStat>> merchantSalesRanking(int limit, String order) {
                log.error("订单服务暂不可用, 仪表盘商家销售额排名降级为空, limit={}, order={}, reason={}",
                        limit, order, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
