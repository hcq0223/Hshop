package com.shopai.user.feign;

import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.common.dto.internal.MerchantInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商家服务 Feign 客户端（用户服务侧）
 * <p>
 * 微服务拆分说明：{@code merchants} 表归属 shopai-merchant 服务。用户服务原先有两处直查：
 * 1. 登录时判断 merchant 角色用户是否已提交商家资料
 *    （原 {@code MerchantRefMapper.selectIdByUserId}，经 {@code users.id} 直查 merchants）；
 * 2. 管理员仪表盘「商家销售额 Top/Bottom」原先在 {@code DashboardMapper.xml} 中
 *    {@code orders INNER JOIN merchants} 跨库联查店铺名。
 * <p>
 * 按「独立库」处理：merchants 表只有商家服务能访问，上述两处改为本 Feign 客户端调用
 * 商家服务内部接口。所有接口均为内部接口（网关不暴露，仅服务间直连）。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_MERCHANT,
        path = InternalApiPaths.MERCHANT_INTERNAL,
        contextId = "user-merchant",
        fallbackFactory = MerchantInternalClientFallback.class
)
public interface MerchantInternalClient {

    /**
     * 按 userId 查询商家（替代原 MerchantRefMapper.selectIdByUserId）
     * <p>
     * 用途：登录时判断 merchant 角色用户是否已提交过商家入驻信息。
     *
     * @param userId 用户ID
     * @return 商家信息；该用户尚未入驻时 data 为 null
     */
    @GetMapping("/by-user")
    com.shopai.common.Result<MerchantInfo> getByUserId(@RequestParam("userId") Long userId);

    /**
     * 按商家ID批量查询店铺简要信息（店铺名/Logo）
     * <p>
     * 用途：管理员仪表盘商家销售额排名原先靠 JOIN 取 shop_name，
     * 拆分后由本接口批量补齐，再在用户服务侧组装。
     *
     * @param ids 逗号分隔的商家ID列表，如 "1,2,3"
     * @return 店铺简要信息列表
     */
    @GetMapping("/briefs")
    com.shopai.common.Result<List<MerchantBrief>> listBriefs(@RequestParam("ids") String ids);

    /**
     * 按店铺名关键字查询命中的 userId 集合
     * <p>
     * 用途：用户服务后台用户列表搜索原为 {@code users LEFT JOIN merchants m}
     * 按 {@code m.shop_name LIKE} 匹配，按「独立库」处理后 merchants 表只有商家服务能查，
     * 故由此接口返回命中的 userId 集合，用户服务再在 users 表本地 IN 过滤。
     *
     * @param keyword 店铺名关键字
     * @param limit   最多返回条数
     * @return 命中的 userId 列表
     */
    @GetMapping("/user-ids-by-shop-name")
    com.shopai.common.Result<List<com.shopai.common.dto.internal.user.UserIdItem>> userIdsByShopName(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "limit", defaultValue = "1000") int limit);
}
