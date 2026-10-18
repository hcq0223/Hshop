package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.UserInfo;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 用户服务 Feign 降级实现（商家服务侧）
 * <p>
 * <b>降级语义按「读/写」区分</b>：
 * <ul>
 *   <li>{@code getUser} → 返回 <b>{@code Result.ok(null)}</b>：调用方
 *       {@code attachUserInfo} 只判 {@code r.getCode() == 200 && r.getData() != null}，
 *       因此 null 数据会被安全跳过，商家详情保持「缺少账号信息」但页面可正常展示；</li>
 *   <li>{@code listUsers} → 空列表：商家索引重建时无法校验「账号是否正常」。
 *       <b>这里必须返回空列表而不是伪造 status=1</b> —— {@code MerchantIndexService}
 *       的逻辑是「取不到用户信息就跳过该商家不进索引」，宁可少索引也不能把
 *       「账号状态未知」的商家放进搜索结果；</li>
 *   <li>{@code activateUser} → 非 200：账号激活失败。调用方
 *       {@code activateUserViaFeign} 对失败的处理是「记录告警、不阻断入驻流程」，
 *       所以返回错误码即可，无需抛异常；</li>
 *   <li>{@code updateAccount} → 非 200：写操作**不能静默成功**，
 *       调用方会据此抛「账号信息更新失败：用户服务不可用」，让商家看到明确失败提示。</li>
 * </ul>
 * <p>
 * <b>为什么不能返回 null</b>（本类初版就是全部 return null，已修正）：
 * 商家服务对这些返回值是**直接解引用**的，例如
 * {@code MerchantServiceImpl.attachUserInfo} 里写的是
 * {@code if (r != null && r.getCode() != null && r.getCode() == 200 ...)}；
 * 虽然多数调用点有 {@code r != null} 判空，但 {@code updateAccount} 处是
 * {@code Result<Integer> r = ...; if (r == null || r.getCode() ...)} 之后还会
 * 走 {@code r.getMessage()} 分支 —— 一旦返回裸 null，调用方稍有不慎就是 NPE，
 * 而降级路径本身是「服务已经出问题」的时刻，最不该再抛出 NPE 掩盖真实原因。
 * 因此本类所有方法都返回**结构完整的 Result**，由调用方按既有逻辑处理。
 */
@Component
public class UserInternalClientFallback implements FallbackFactory<UserInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(UserInternalClientFallback.class);

    @Override
    public UserInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <UserInternalClientFallback-merchant> 进入降级, reason={}", reason, cause);
        }
        return new UserInternalClient() {

            @Override
            public Result<UserInfo> getUser(Long userId) {
                log.warn("用户服务暂不可用, 查询用户信息降级为 null（商家详情将缺少账号信息）, "
                        + "userId={}, reason={}", userId, reason);
                return Result.ok((UserInfo) null);
            }

            @Override
            public Result<List<UserInfo>> listUsers(String ids) {
                // 刻意返回空列表：MerchantIndexService 会据此「跳过这些商家不进索引」，
                // 避免把账号状态未知的商家放进搜索结果。
                log.warn("用户服务暂不可用, 批量查询用户信息降级为空（相关商家将暂不进索引）, "
                        + "ids={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }

            @Override
            public Result<Integer> activateUser(Long userId) {
                log.error("用户服务暂不可用, 激活用户账号失败（不阻断入驻流程）, userId={}, reason={}",
                        userId, reason);
                return Result.error("用户服务暂不可用，激活账号失败");
            }

            @Override
            public Result<Integer> updateAccount(Long userId, String username, String phone, String email) {
                // 写操作不能静默成功：返回错误码，调用方会抛「账号信息更新失败」给商家
                log.error("用户服务暂不可用, 更新账号信息失败, userId={}, reason={}", userId, reason);
                return Result.error("用户服务暂不可用，账号信息更新失败");
            }
        };
    }
}
