package com.shopai.user.controller;

import com.shopai.common.PageRequest;
import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.user.dto.UserDto;
import com.shopai.user.service.CustomerService;
import com.shopai.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员用户管理 + 仪表盘控制器
 * <p>
 * 微服务拆分说明：从原单体 UserController 的「管理员段」拆出，路径规范化（见设计文档 5.1）：
 * - 旧 /api/user/admin/dashboard        → 新 /api/admin/dashboard
 * - 旧 /api/user/admin/dashboard-stats  → 新 /api/admin/dashboard/stats
 * - 旧 /api/user/admin/getUsers         → 新 /api/admin/users          (GET)
 * - 旧 /api/user/admin/getUser          → 新 /api/admin/users/{id}     (GET)
 * - 旧 /api/user/admin/getCustomers     → 新 /api/admin/customers      (GET)
 * - 旧 /api/user/admin/getCustomer      → 新 /api/admin/customers/{userId} (GET)
 * - 旧 /api/user/admin/updateStatus     → 新 /api/admin/users/{id}/status (PUT)
 * - 旧 /api/user/admin/deleteUser       → 新 /api/admin/users/{id}     (DELETE)
 * - 旧 /api/user/admin/resetPassword    → 新 /api/admin/users/{id}/reset-password (POST)
 * <p>
 * 鉴权改造：原控制器用 jwtUtil.getRole(token) 校验 admin；拆分后由网关统一校验
 * /api/admin/** 前缀需 admin 角色（JwtAuthGlobalFilter），本控制器读取 X-User-Role 头兜底。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    /** users.role 的合法取值（网关的角色校验、各端路由守卫都依赖这三个值） */
    private static final java.util.Set<String> ALLOWED_ROLES =
            java.util.Set.of("customer", "merchant", "admin");

    private final UserService userService;
    private final CustomerService customerService;

    /**
     * 管理员权限兜底校验（网关已校验 /api/admin/** 需 admin，此处二次确认）
     */
    private void checkAdmin(HttpServletRequest request) {
        if (!CurrentUser.isRole(request, "admin")) {
            throw new RuntimeException("无权操作");
        }
    }

    /**
     * 管理员端仪表盘数据（汇总统计）
     * 旧路径：/api/user/admin/dashboard → 新路径：/api/admin/dashboard
     */
    @GetMapping("/dashboard")
    public Result<?> getUserStats(HttpServletRequest request) {
        checkAdmin(request);
        return Result.ok("管理员仪表盘数据", userService.getUserStats());
    }

    /**
     * 管理员仪表盘全量统计（含趋势图数据）
     * 旧路径：/api/user/admin/dashboard-stats → 新路径：/api/admin/dashboard/stats
     */
    @GetMapping("/dashboard/stats")
    public Result<?> getDashboardStats(HttpServletRequest request) {
        checkAdmin(request);
        return Result.ok("仪表盘统计数据", userService.getDashboardStats());
    }

    /**
     * 管理员查询用户列表
     * 旧路径：/api/user/admin/getUsers → 新路径：/api/admin/users
     */
    @GetMapping("/users")
    public Result<?> listUsers(@RequestParam(defaultValue = "1") Integer page,
                               @RequestParam(defaultValue = "10") Integer size,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String role,
                               @RequestParam(required = false) Integer status,
                               HttpServletRequest request) {
        checkAdmin(request);
        PageRequest req = new PageRequest();
        req.setPage(page);
        req.setSize(size);
        req.setKeyword(keyword);
        return Result.ok(userService.listUsers(req, role, status));
    }

    /**
     * 管理员查询用户信息
     * 旧路径：/api/user/admin/getUser → 新路径：/api/admin/users/{id}
     */
    @GetMapping("/users/{id}")
    public Result<?> getUser(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        UserDto byId = userService.getById(id);
        return Result.ok("用户信息 ", byId);
    }

    /**
     * 管理员查询客户列表
     * 旧路径：/api/user/admin/getCustomers → 新路径：/api/admin/customers
     */
    @GetMapping("/customers")
    public Result<?> listCustomer(@RequestParam(defaultValue = "1") Integer page,
                                  @RequestParam(defaultValue = "10") Integer size,
                                  @RequestParam(required = false) String keyword,
                                  HttpServletRequest request) {
        checkAdmin(request);
        PageRequest req = new PageRequest();
        req.setPage(page);
        req.setSize(size);
        req.setKeyword(keyword);
        return Result.ok("客户列表 ", customerService.searchCustomersByNickname(req));
    }

    /**
     * 管理员查询客户详情（按 userId）
     * 旧路径：/api/user/admin/getCustomer → 新路径：/api/admin/customers/{userId}
     */
    @GetMapping("/customers/{userId}")
    public Result<?> getCustomer(@PathVariable Long userId, HttpServletRequest request) {
        checkAdmin(request);
        return Result.ok("客户userId: " + userId + " 详情: ", customerService.getCustomerDetailByUserId(userId));
    }

    /**
     * 更新用户状态（含角色）
     * 旧路径：/api/user/admin/updateStatus → 新路径：/api/admin/users/{id}/status
     * @param id 要改的用户id
     * @param status 要改的状态：只能 0（禁用/待激活）或 1（正常）
     * @param role 角色（可选，不传则不改）
     */
    @PutMapping("/users/{id}/status")
    public Result<?> updateStatus(@PathVariable Long id,
                                  @RequestParam Integer status,
                                  @RequestParam(required = false) String role,
                                  HttpServletRequest request) {
        checkAdmin(request);

        // 入参校验（原实现缺失）：status 直接写进 users.status，不校验取值范围。
        // 实测传 status=9 也返回「操作成功」，而 users.status 只是 tinyint，
        // 脏值会让所有依赖「status=1 表示账号正常」的判断（登录、
        // 商家 is_verified 联动、账号可用性）静默失效，且很难排查。
        if (status == null || (status != 0 && status != 1)) {
            return Result.error(400, "用户状态只能是 0（禁用/待激活）或 1（正常）");
        }
        // 角色同理：拼错的角色会让该账号在网关的角色校验里永远匹配不上
        if (role != null && !role.isBlank() && !ALLOWED_ROLES.contains(role)) {
            return Result.error(400, "非法角色: " + role + "，只能是 " + ALLOWED_ROLES);
        }

        userService.updateStatus(id, status);
        if (role != null && !role.isBlank()) {
            userService.updateRole(id, role);
        }
        log.info("管理员更新了用户id: {} 状态:{} 角色:{}", id, status, role);
        return Result.ok("用户id: " + id + " 更新成功");
    }

    /**
     * 管理员销号
     * 旧路径：/api/user/admin/deleteUser → 新路径：/api/admin/users/{id}
     */
    @DeleteMapping("/users/{id}")
    public Result<?> deleteUser(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        userService.deleteUser(id);
        log.info("管理员删除了用户id: {} ", id);
        return Result.ok("用户id: " + id + " 删除成功");
    }

    /**
     * 管理员重置密码
     * 旧路径：/api/user/admin/resetPassword → 新路径：/api/admin/users/{id}/reset-password
     */
    @PostMapping("/users/{id}/reset-password")
    public Result<?> resetPassword(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        userService.resetPassword(id);
        log.info("管理员重置了用户id: {} 密码", id);
        return Result.ok("用户id: " + id + " 密码重置成功");
    }

}
