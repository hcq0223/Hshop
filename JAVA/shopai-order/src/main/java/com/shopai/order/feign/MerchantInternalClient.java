package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.MerchantFunds;
import com.shopai.common.dto.internal.MerchantFundsChangeRequest;
import com.shopai.common.dto.internal.MerchantInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商家服务 Feign 客户端（订单服务侧）
 * <p>
 * 微服务拆分说明：{@code merchants} 表归属 shopai-merchant 服务。订单服务原先有两类直查/直写：
 * 1. 身份映射：{@code OrderController.resolveMerchantId} 通过
 *    {@code MerchantRefMapper.selectByUserId(userId)} 由 userId 解析 merchantId；
 * 2. 资金变动：payOrder（累营收+可用余额）、requestRefund（冻结）、approveRefund /
 *    rejectRefund / forceCancelByAdmin / forceRefundByAdmin / forceRefundComplete
 *    共 8 处 {@code MerchantRefMapper.selectById + updateById} 读改写
 *    total_revenue / available_balance / frozen_balance。
 * <p>
 * 按「独立库」处理后，改为本 Feign 客户端调用商家服务内部接口：
 * - 身份映射走 {@link #getByUserId}；
 * - 资金变动走 {@link #changeFunds}，由商家服务在本地事务内**原子累加**完成，
 *   既消除跨库写，也避免原「读-改-写」在并发下的资金覆盖问题。
 * <p>
 * 安全说明：/api/merchant/internal/** 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_MERCHANT,
        path = InternalApiPaths.MERCHANT_INTERNAL,
        contextId = "order-merchant",
        fallbackFactory = MerchantInternalClientFallback.class
)
public interface MerchantInternalClient {

    /**
     * 按 userId 查询商家（替代原 MerchantRefMapper.selectByUserId）
     * <p>
     * 用途：订单接口把网关 X-User-Id 解析为 merchantId。
     *
     * @param userId 用户ID
     * @return 商家信息；该用户尚未入驻时 data 为 null
     */
    @GetMapping("/by-user")
    Result<MerchantInfo> getByUserId(@RequestParam("userId") Long userId);

    /**
     * 查询商家当前资金（只读，用于前置校验）
     *
     * @param merchantId 商家ID
     * @return 资金信息；商家不存在时 data 为 null
     */
    @GetMapping("/funds")
    Result<MerchantFunds> getFunds(@RequestParam("merchantId") Long merchantId);

    /**
     * 原子变更商家资金（替代原 selectById + updateById 的读改写）
     * <p>
     * 语义：请求体中的各 Delta 字段为「增量」，null 表示本次不变动该字段，
     * 由商家服务在同一本地事务内累加，保证并发安全。
     * <p>
     * 用法示例：
     * - 支付成功：revenueDelta=+payAmount, availableDelta=+payAmount
     * - 申请退款：frozenDelta=+payAmount, availableDelta=-payAmount
     * - 同意退款：frozenDelta=-payAmount, revenueDelta=-refund, availableDelta=+payAmount-refund
     *
     * @param request 资金变动请求
     * @return 通用 Result（code=200 成功，code=500 表示商家不存在等业务失败）
     */
    @PostMapping("/funds/change")
    Result<?> changeFunds(@RequestBody MerchantFundsChangeRequest request);

    /**
     * 按商家ID批量查询店铺简要信息（店铺名/Logo）
     * <p>
     * 用途：替代 {@code OrderMapper.xml} 中对 merchants 表 JOIN 的「店铺名/Logo」部分。
     * 订单列表/详情查询后由 OrderEnrichService 一次性批量补齐，
     * 填充到 {@code Order.merchant} 与 {@code OrderItem.merchant}（前端读取店铺名/Logo）。
     *
     * @param ids 逗号分隔的商家ID列表，如 "1,2,3"
     * @return 店铺简要信息列表
     */
    @GetMapping("/briefs")
    Result<List<com.shopai.common.dto.internal.merchant.MerchantBrief>> listBriefs(@RequestParam("ids") String ids);
}
