package com.shopai.seckill.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.CustomerIdPair;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 Feign 客户端（秒杀服务侧）
 * <p>
 * 唯一用途：把网关下发的 {@code X-User-Id}（users.id）映射为下单需要的
 * {@code customerId}（customers.id）。
 * <p>
 * 为什么抢购时要立刻解析并<b>存进 seckill_record.customer_id</b>：
 * MQ 消费创建订单时已经没有用户请求上下文了（异步线程里没有 X-User-Id），
 * 所以身份必须在抢购那一刻解析好、随消息带走。
 * 若当时映射不出来（用户还没建客户资料），customer_id 存 null，
 * 由超时任务重试解析；一直解析不出来则判失败并回补库存 ——
 * 这比「扣了库存却永远下不了单」要好。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_USER,
        path = InternalApiPaths.CUSTOMER_INTERNAL,
        contextId = "seckill-customer",
        fallbackFactory = CustomerInternalClientFallback.class
)
public interface CustomerInternalClient {

    /**
     * 按用户ID批量查询客户ID映射。
     *
     * @param userIds 逗号分隔的用户ID列表
     */
    @GetMapping("/ids")
    Result<List<CustomerIdPair>> listIdsByUserIds(@RequestParam("userIds") String userIds);
}
