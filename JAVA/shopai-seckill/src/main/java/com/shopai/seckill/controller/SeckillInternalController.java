package com.shopai.seckill.controller;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.seckill.service.SeckillOrderResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 秒杀服务内部接口（供其它服务 Feign 直连，不对网关暴露）
 * <p>
 * 网关对 {@code /api/seckill/internal/**} 没有路由（被 block-internal 规则
 * 指向 {@code no://op} 返回 503），因此这些接口只有服务间调用能得到。
 */
@RestController
@RequestMapping(InternalApiPaths.SECKILL_INTERNAL)
public class SeckillInternalController {

    private static final Logger log = LoggerFactory.getLogger(SeckillInternalController.class);

    @Autowired
    private SeckillOrderResultService orderResultService;

    /**
     * 订单已创建回调（订单服务调用）。
     * <p>
     * 幂等：按订单号里的抢购记录ID判定，重复回调只会落账一次。
     * <b>本接口必须返回 200 才算成功</b> —— 返回非 200 时订单服务虽然会吞掉
     * 异常（不让订单回滚），但秒杀侧的消息不会被置为 done，
     * 会被重投任务反复重试直到成功。
     *
     * @param result 下单结果（必须含 orderNo，格式 {@code SK+recordId}）
     * @return 处理结果描述
     */
    @PostMapping("/order-created")
    public Result<String> orderCreated(@RequestBody SeckillOrderResult result) {
        try {
            String msg = orderResultService.onOrderCreated(result);
            return Result.ok(msg);
        } catch (Exception e) {
            // 返回失败而不抛异常：让订单服务拿到明确的失败响应（它会记日志并继续），
            // 同时秒杀侧的消息保持未完成状态，等待下一次重投。
            log.error("成单回调处理失败, orderNo={}", result == null ? null : result.getOrderNo(), e);
            return Result.error("成单回调处理失败: " + e.getMessage());
        }
    }
}
