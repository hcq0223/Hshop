package com.shopai.seckill.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.MerchantInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 商家服务 Feign 客户端（秒杀服务侧）
 * <p>
 * 用途：把当前登录的 {@code userId} 映射为 {@code merchantId}，
 * 用于商家端接口的归属校验。
 * <p>
 * <b>为什么必须服务端解析而不是信任前端传的 merchantId</b>：
 * 如果卖家端接口接受前端传来的 merchantId，商家 A 只要把参数改成 B 的 ID，
 * 就能查看、修改、下线别人的秒杀活动。这里用「当前登录 userId → 查出的
 * merchantId」作为唯一可信来源，前端传什么都不看。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_MERCHANT,
        path = InternalApiPaths.MERCHANT_INTERNAL,
        contextId = "seckill-merchant",
        fallbackFactory = MerchantInternalClientFallback.class
)
public interface MerchantInternalClient {

    /**
     * 按用户ID查询商家信息。
     *
     * @param userId 用户ID（来自网关 X-User-Id）
     * @return 商家信息；该用户未入驻时 data 为 null
     */
    @GetMapping("/by-user")
    Result<MerchantInfo> getByUserId(@RequestParam("userId") Long userId);
}
