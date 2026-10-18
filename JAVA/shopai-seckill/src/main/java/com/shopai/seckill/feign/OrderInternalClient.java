package com.shopai.seckill.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.OrderInfo;
import com.shopai.common.dto.internal.seckill.SeckillOrderCreateRequest;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 订单服务 Feign 客户端（秒杀服务侧）
 * <p>
 * 秒杀服务<b>不自己建订单表</b>，真实订单一律交给 shopai-order 创建，
 * 保证「订单」这个概念全平台只有一处权威实现（状态机、支付、退款、结算都在那边）。
 * 秒杀侧只保留 {@code seckill_order} 流水视图。
 * <p>
 * 幂等约定：下单请求带上固定的 {@code orderNo}（{@code SK + recordId}），
 * 订单服务按订单号查重，重复投递返回同一笔订单。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_ORDER,
        path = InternalApiPaths.ORDER_INTERNAL,
        contextId = "seckill-order",
        fallbackFactory = OrderInternalClientFallback.class
)
public interface OrderInternalClient {

    /**
     * 秒杀下单（不扣主库存，成交价用秒杀价，按 orderNo 幂等）。
     *
     * @param req 下单请求
     * @return 下单结果（含订单ID/订单号与是否命中幂等）
     */
    @PostMapping("/seckill/order")
    Result<SeckillOrderResult> createSeckillOrder(@RequestBody SeckillOrderCreateRequest req);

    /**
     * 按订单ID查询订单（用于对账与流水补写）。
     *
     * @param orderId 订单ID
     */
    @GetMapping("/{orderId}")
    Result<OrderInfo> getOrder(@PathVariable("orderId") Long orderId);
}