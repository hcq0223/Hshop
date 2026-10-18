package com.shopai.user.controller;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.UserInfo;
import com.shopai.common.dto.internal.index.UserIndexItem;
import com.shopai.user.entity.User;
import com.shopai.user.mapper.UserMapper;
import com.shopai.user.service.UserIndexAssembler;
import com.shopai.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户服务内部接口（供其他服务通过 Feign 调用，不对网关暴露）
 * <p>
 * 微服务拆分说明：{@code users} 表归属 shopai-user 服务。按「独立库」处理跨服务访问后，
 * 原先其他服务对 users 表的直查/直写全部改为调用本控制器：
 * - shopai-merchant：商家入驻后激活账号（原 UserRefMapper.updateStatusToActive）、
 *   商家自助修改账号信息（原 UserRefMapper.selectByUserId + updateById）、
 *   商家详情联查用户信息（原 MerchantMapper.xml 的 JOIN users）；
 * - shopai-search：商家索引重建需要 users 的账号状态与创建时间（原 JOIN users）；
 * - shopai-order / shopai-merchant / shopai-product：按 userId 解析商家/客户身份。
 * <p>
 * 安全说明：{@code /api/user/internal/**} 不应被网关对外暴露（见 shopai-gateway 的
 * application.yml：内部前缀不配置路由 → 网关默认 404），仅服务间 Feign 直连。
 */
@RestController
@RequestMapping(InternalApiPaths.USER_INTERNAL)
@RequiredArgsConstructor
public class UserInternalController {

    private static final Logger log = LoggerFactory.getLogger(UserInternalController.class);

    private final UserMapper userMapper;
    // 列表查询改造新增：user 索引视图组装（含客户昵称、店铺名冗余字段）
    private final UserIndexAssembler userIndexAssembler;
    // 账号信息更新委托给 Service 执行（含缓存清理与索引同步，避免两处实现漏步骤）
    private final UserService userService;

    /**
     * 按用户ID查询用户信息
     * <p>
     * 原调用方：商家服务 UserRefMapper.selectByUserId（共享单库直查）。
     *
     * @param userId 用户ID
     * @return 用户信息；不存在时 data 为 null
     */
    @GetMapping("/{userId}")
    public Result<UserInfo> getById(@PathVariable("userId") Long userId) {
        User user = userMapper.selectById(userId);
        return Result.ok(toInfo(user));
    }

    /**
     * 批量查询用户信息
     * <p>
     * 原调用方：需要一次性补齐多个用户信息的聚合场景（如商家索引全量重建）。
     *
     * @param ids 逗号分隔的用户ID列表，如 "1,2,3"
     * @return 用户信息列表（不存在的ID自动跳过）
     */
    @GetMapping("/batch")
    public Result<List<UserInfo>> listByIds(@RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<User> users = userMapper.selectBatchIds(idList);
        List<UserInfo> result = new ArrayList<>(users.size());
        for (User user : users) {
            result.add(toInfo(user));
        }
        return Result.ok(result);
    }

    /**
     * 激活用户账号（users.status: 0 → 1）
     * <p>
     * 原调用方：商家服务 UserRefMapper.updateStatusToActive
     * （商家提交入驻资料后激活账号）。
     * <p>
     * <b>实现说明</b>：本接口**委托 {@link UserService#activateUser}** 执行，
     * 而不是直接调 mapper。因为「激活」不只是一条 UPDATE：
     * 还需清用户缓存（{@code cache:user:{id}}、用户统计、仪表盘）
     * 并同步 user 索引（{@code status} 是后台用户列表的筛选字段）。
     * 该 Service 方法内已包含这两步（见 {@code UserServiceImpl.activateUser}），
     * 委托调用可从根上避免「接口自己写一遍、漏掉后续步骤」的问题。
     *
     * @param userId 用户ID
     * @return 影响行数（成功恒为 1；语义与改造前保持一致）
     */
    @PostMapping("/{userId}/activate")
    public Result<Integer> activate(@PathVariable("userId") Long userId) {
        try {
            userService.activateUser(userId);
            return Result.ok(1);
        } catch (RuntimeException e) {
            log.warn("内部接口激活用户失败: userId={}, msg={}", userId, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /**
     * 更新用户账号基础信息（username / phone / email）
     * <p>
     * 原调用方：商家服务 UserRefMapper.selectByUserId + updateById
     * （商家自助修改账号信息 /api/merchant/me/account）。
     * <p>
     * 传 null 或空串表示不修改该字段。
     * <p>
     * <b>实现说明（重要）</b>：本接口**委托给 {@link UserService#updateAccount}** 执行，
     * 而不是自己写一遍 mapper 更新。原因是「改账号」不只是一条 UPDATE，还附带两件事：
     * <ol>
     *   <li><b>清缓存</b>：{@code UserServiceImpl.getById} 会把用户缓存 300 秒
     *       （{@code cache:user:{id}}），用户统计也有缓存；</li>
     *   <li><b>同步 user 索引</b>：用户名/手机/邮箱是后台用户列表的检索字段，
     *       不同步会导致「按新用户名搜不到该用户」。</li>
     * </ol>
     * 早先这里是一段独立的 mapper 更新逻辑，遗漏了上面两步 ——
     * 于是商家改完账号后，用户详情缓存与 ES 索引都停留在旧值。
     * 复用 Service 方法可从根上避免这类「同一业务两处实现、其中一处漏步骤」的问题。
     *
     * @param userId   用户ID
     * @param username 新用户名（可选）
     * @param phone    新手机号（可选）
     * @param email    新邮箱（可选）
     * @return 影响行数（成功恒为 1；语义与改造前保持一致）
     */
    @PostMapping("/{userId}/account")
    public Result<Integer> updateAccount(@PathVariable("userId") Long userId,
                                         @RequestParam(value = "username", required = false) String username,
                                         @RequestParam(value = "phone", required = false) String phone,
                                         @RequestParam(value = "email", required = false) String email) {
        try {
            userService.updateAccount(userId, username, phone, email);
            return Result.ok(1);
        } catch (RuntimeException e) {
            // Service 侧以「用户不存在 / 账号信息更新失败」等业务信息抛 RuntimeException，
            // 这里转成统一的 Result 错误结构，保持内部接口的响应契约不变。
            log.warn("内部接口更新账号信息失败: userId={}, msg={}", userId, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    // ==================== 列表查询改造：user 索引回源 ====================

    /**
     * 取单个用户的索引视图（搜索服务消费 user.changed 后回源调用）
     * <p>
     * 微服务拆分说明（列表查询改造）：后台用户列表改由 ES 承载后，索引文档需要
     * 「客户昵称 + 店铺名」两个冗余检索字段（原靠 {@code users LEFT JOIN customers/merchants}）。
     * 这两个字段只有用户服务能组装（昵称查本地 customers，店铺名 Feign 调商家服务），
     * 故由本接口回源提供给搜索服务。
     * <p>
     * 安全说明：返回的索引视图**不含** passwordHash 等凭据字段。
     *
     * @param userId 用户ID
     * @return 索引视图；用户不存在时 data 为 null
     */
    @GetMapping("/index/user/{userId}")
    public Result<UserIndexItem> indexUser(@PathVariable("userId") Long userId) {
        return Result.ok(userIndexAssembler.assemble(userId));
    }

    /**
     * 分页取用户索引视图（user 索引全量重建用）
     * <p>
     * 分批拉取的原因：用户量通常远大于其他实体，一次性全量组装会占用大量内存与报文。
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     */
    @GetMapping("/index/users")
    public Result<List<UserIndexItem>> indexUsers(@RequestParam(value = "page", defaultValue = "1") int page,
                                                  @RequestParam(value = "size", defaultValue = "500") int size) {
        return Result.ok(userIndexAssembler.assemblePage(page, size));
    }

    // ==================== 辅助方法 ====================

    /** User 实体 → 跨服务 UserInfo DTO（只取叶子字段，不带任何 Mapper 引用） */
    private UserInfo toInfo(User user) {
        if (user == null) {
            return null;
        }
        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setPhone(user.getPhone());
        info.setEmail(user.getEmail());
        info.setRole(user.getRole());
        info.setStatus(user.getStatus());
        info.setCreateTime(user.getCreateTime());
        info.setUpdateTime(user.getUpdateTime());
        return info;
    }

    /** 解析逗号分隔的ID串，忽略非法片段 */
    private static List<Long> parseIds(String ids) {
        List<Long> result = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            return result;
        }
        for (String part : ids.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.valueOf(s));
            } catch (NumberFormatException ignored) {
                // 忽略非法ID片段
            }
        }
        return result;
    }
}
