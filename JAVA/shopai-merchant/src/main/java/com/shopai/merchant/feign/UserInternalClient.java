package com.shopai.merchant.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.UserInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务内部接口 Feign 客户端（users 表，商家服务侧）
 * <p>
 * 微服务拆分说明：{@code users} 表归属 shopai-user 服务。商家服务原先直查/直写该表，
 * 按「独立库」处理后全部改为本 Feign 客户端：
 * 1. 入驻后激活账号（原 {@code UserRefMapper.updateStatusToActive}）→ {@link #activateUser}
 * 2. 商家自助修改账号信息（原 {@code UserRefMapper.selectByUserId + updateById}）→ {@link #updateAccount}
 * 3. 商家详情联查用户信息（原 {@code MerchantMapper.xml} 的 {@code INNER JOIN users}）→ {@link #getUser}
 * 4. 商家索引按「账号正常」过滤（原 {@code MerchantRefMapper.xml} 的 {@code JOIN users u ... u.status = 1}）
 *    → {@link #listUsers}
 * <p>
 * 安全说明：{@code /api/user/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_USER,
        path = InternalApiPaths.USER_INTERNAL,
        contextId = "merchant-user",
        fallbackFactory = UserInternalClientFallback.class
)
public interface UserInternalClient {

    /**
     * 按用户ID查询用户信息（替代原 UserRefMapper.selectByUserId）
     *
     * @param userId 用户ID
     * @return 用户信息；不存在时 data 为 null
     */
    @GetMapping("/{userId}")
    Result<UserInfo> getUser(@PathVariable("userId") Long userId);

    /**
     * 批量查询用户信息（商家索引按账号状态过滤用）
     *
     * @param ids 逗号分隔的用户ID列表，如 "1,2,3"
     * @return 用户信息列表
     */
    @GetMapping("/batch")
    Result<List<UserInfo>> listUsers(@RequestParam("ids") String ids);

    /**
     * 激活用户账号（替代原 UserRefMapper.updateStatusToActive）
     * <p>
     * 商家提交入驻资料后调用：users.status 由 0 改为 1。
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    @PostMapping("/{userId}/activate")
    Result<Integer> activateUser(@PathVariable("userId") Long userId);

    /**
     * 更新用户账号基础信息（替代原 UserRefMapper.selectByUserId + updateById）
     * <p>
     * 商家自助修改账号（/api/merchant/me/account）：username / phone / email。
     * 传 null 或空串表示不修改该字段。
     *
     * @param userId   用户ID
     * @param username 新用户名（可选）
     * @param phone    新手机号（可选）
     * @param email    新邮箱（可选）
     * @return 影响行数
     */
    @PostMapping("/{userId}/account")
    Result<Integer> updateAccount(@PathVariable("userId") Long userId,
                                  @RequestParam(value = "username", required = false) String username,
                                  @RequestParam(value = "phone", required = false) String phone,
                                  @RequestParam(value = "email", required = false) String email);
}
