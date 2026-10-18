package com.shopai.user.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.user.UserIdItem;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 商家服务 Feign 降级实现（用户服务侧）
 * <p>
 * <b>降级语义</b>（三个方法都是只读，失败时「少展示一点」比整页报错更合理）：
 * <ul>
 *   <li>{@code getByUserId} → null：登录时无法判定 merchant 角色用户是否已入驻，
 *       {@code checkAccountStatus} 按「无商家资料」处理（需补填信息），
 *       表现为「该账号已禁用」或「需完善资料」的业务提示，而非 500；</li>
 *   <li>{@code listBriefs} → 空列表：仪表盘「商家销售额排名」缺少店铺名（显示为空），
 *       金额排名本身仍可用（商户ID与销售额来自订单服务）；</li>
 *   <li>{@code userIdsByShopName} → 空列表：后台用户列表按店铺名搜索退化为
 *       「只能按用户名/手机/邮箱/客户昵称匹配」，不会搜出错误结果。</li>
 * </ul>
 */
@Component
public class MerchantInternalClientFallback implements FallbackFactory<MerchantInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(MerchantInternalClientFallback.class);

    @Override
    public MerchantInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <MerchantInternalClient-user> 进入降级, reason={}", reason, cause);
        }
        return new MerchantInternalClient() {

            @Override
            public Result<MerchantInfo> getByUserId(Long userId) {
                log.warn("商家服务暂不可用, 无法判定用户是否已入驻商家, userId={}, reason={}", userId, reason);
                return Result.ok((MerchantInfo) null);
            }

            @Override
            public Result<List<MerchantBrief>> listBriefs(String ids) {
                log.warn("商家服务暂不可用, 批量查询店铺信息降级为空列表（销售额排名将缺少店铺名）, "
                        + "ids={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }

            @Override
            public Result<List<UserIdItem>> userIdsByShopName(String keyword, int limit) {
                log.warn("商家服务暂不可用, 按店铺名匹配用户降级为空（该维度搜索暂不可用）, "
                        + "keyword={}, reason={}", keyword, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
