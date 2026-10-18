package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantFunds;
import com.shopai.common.dto.internal.MerchantFundsChangeRequest;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 商家服务 Feign 降级实现（订单服务侧）
 * <p>
 * <b>降级语义按「方法是否影响资金/事务正确性」区分，不能一刀切</b>：
 * <ul>
 *   <li><b>只读查询</b>（by-user / funds / briefs）→ 安全降级为 null 或空列表，
 *       让订单列表「缺少店铺名」而不是整页报错；</li>
 *   <li><b>资金变更</b>（funds/change）→ <b>必须抛出异常</b>，绝不能静默成功。
 *       资金变更是下单支付/退款链路的关键一步，静默成功会导致
 *       「订单状态已流转，但商户余额没变」的资损；抛异常才能让
 *       {@code @GlobalTransactional} 全局回滚（Seata 未启用时至少让本地事务回滚）。</li>
 * </ul>
 */
@Component
public class MerchantInternalClientFallback implements FallbackFactory<MerchantInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(MerchantInternalClientFallback.class);

    @Override
    public MerchantInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <MerchantInternalClient-order> 进入降级, reason={}", reason, cause);
        }
        return new MerchantInternalClient() {

            @Override
            public Result<MerchantInfo> getByUserId(Long userId) {
                // 读操作：返回 null，调用方（OrderEnrichService.resolveMerchantIdByUserId）
                // 已按 null 处理（无法解析商户身份 → 由 Controller 返回业务提示）
                log.warn("商家服务暂不可用, 无法按 userId 解析商家, userId={}, reason={}", userId, reason);
                return Result.ok((MerchantInfo) null);
            }

            @Override
            public Result<MerchantFunds> getFunds(Long merchantId) {
                log.warn("商家服务暂不可用, 查询商家资金降级为 null, merchantId={}, reason={}", merchantId, reason);
                return Result.ok((MerchantFunds) null);
            }

            @Override
            public Result<?> changeFunds(MerchantFundsChangeRequest request) {
                // 写操作：必须失败，不能吞掉。静默成功会造成商户资金与订单状态不一致（资损）。
                log.error("商家服务暂不可用, 资金变更失败（订单链路将回滚）, request={}, reason={}", request, reason);
                // 返回非 200 的 Result 即可让调用方 changeMerchantFunds 抛异常，
                // 与「服务端业务校验失败」的处理路径保持一致。
                return Result.error("商家服务暂不可用，资金变更失败");
            }

            @Override
            public Result<List<MerchantBrief>> listBriefs(String ids) {
                // 读操作：返回空列表，订单列表/详情缺少店铺名但页面正常展示
                log.warn("商家服务暂不可用, 批量查询店铺信息降级为空列表, ids={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
