package com.shopai.product.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商家服务 Feign 客户端（商品服务侧）
 * <p>
 * 微服务拆分说明：{@code merchants} 表归属 shopai-merchant 服务。商品服务原先有两处直查：
 * 1. {@code ProductController.addProduct} 通过 {@code MerchantRefMapper.selectByUserId}
 *    解析当前登录商户的 merchantId；
 * 2. {@code ProductMapper.xml} 的 {@code selectDetailById / selectHotOnSale}
 *    通过 {@code JOIN merchants} 取店铺名与 Logo。
 * <p>
 * 按「独立库」处理后，merchants 表只有商家服务能访问，上述两处改为本 Feign 客户端。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_MERCHANT,
        path = InternalApiPaths.MERCHANT_INTERNAL,
        contextId = "product-merchant",
        fallbackFactory = MerchantInternalClientFallback.class
)
public interface MerchantInternalClient {

    /**
     * 按 userId 查询商家（替代原 MerchantRefMapper.selectByUserId）
     * <p>
     * 用途：商户新增商品时，从网关 X-User-Id 解析出的 userId 解析出 merchantId。
     *
     * @param userId 用户ID
     * @return 商家信息；该用户尚未入驻时 data 为 null
     */
    @GetMapping("/by-user")
    Result<MerchantInfo> getByUserId(@RequestParam("userId") Long userId);

    /**
     * 按商家ID批量查询店铺简要信息（店铺名/Logo）
     * <p>
     * 用途：替代 {@code ProductMapper.xml} 中对 merchants 表 JOIN 的「店铺名/Logo」部分，
     * 由 Service 层一次性批量补齐，避免 N+1 调用。
     *
     * @param ids 逗号分隔的商家ID列表，如 "1,2,3"
     * @return 店铺简要信息列表
     */
    @GetMapping("/briefs")
    Result<List<MerchantBrief>> listBriefs(@RequestParam("ids") String ids);
}
