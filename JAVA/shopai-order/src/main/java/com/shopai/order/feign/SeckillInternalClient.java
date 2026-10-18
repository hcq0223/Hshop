package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 秒杀服务 Feign 客户端（订单服务侧）
 * <p>
 * 用途：订单创建成功后<b>回调</b>秒杀服务落账（抢购记录置成功、写流水、
 * 锁定库存转已售、累加销量、把本地消息置 done）。
 * <p>
 * <b>为什么由订单服务主动回调、而不是让秒杀服务轮询</b>：
 * 轮询需要秒杀服务不停地查订单服务「我的订单建好了吗」，问的次数远多于
 * 真正需要的次数（大量问询的结果是「还没好」），并发高时会把订单服务压垮。
 * 回调是「有结果了才通知」，成本与真实事件数成正比。
 * <p>
 * <b>方向说明</b>：这里形成了 seckill → order（下单）与 order → seckill（回调）
 * 的双向调用。两个方向都是运行时的 HTTP 调用，不是 Spring Bean 依赖，
 * 因此不会有循环依赖问题；服务之间通过 Nacos 互相发现，也不存在启动顺序问题。
 * <p>
 * 注意：本回调<b>不是强依赖</b>。订单服务调用它失败时会吞掉异常（见
 * {@code OrderInternalController#createSeckillOrder} 的注释），
 * 由秒杀侧的消息重投兜底 —— 因此这里的降级只需要返回失败让调用方记日志。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_SECKILL,
        path = InternalApiPaths.SECKILL_INTERNAL,
        contextId = "order-seckill",
        fallbackFactory = SeckillInternalClientFallback.class
)
public interface SeckillInternalClient {

    /**
     * 通知秒杀服务：订单已创建（幂等，按 orderNo 判定）。
     *
     * @param result 下单结果
     */
    @PostMapping("/order-created")
    Result<String> orderCreated(@RequestBody SeckillOrderResult result);
}
