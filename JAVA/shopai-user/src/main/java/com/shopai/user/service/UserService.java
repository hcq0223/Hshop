package com.shopai.user.service;

import com.shopai.common.PageRequest;
import com.shopai.common.PageResult;
import com.shopai.user.dto.LoginRequest;
import com.shopai.user.dto.RegisterRequest;
import com.shopai.user.dto.UserDto;
import com.shopai.user.entity.User;

import java.util.Map;

/**
 * 用户服务接口（认证 + 用户管理 + 管理员仪表盘）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.UserService 迁移至 shopai-user 服务。
 * 主要改造点：
 * 1. 鉴权方式：原方法通过 token 解析角色/用户ID（jwtUtil.getRole(token) / getUserId(token)），
 *    拆分后由「网关统一认证」写入 X-User-* 头，Controller 读取头后把 userId/role 显式传入本类，
 *    本类不再持有 JwtUtil 做权限判断（仅登录签发 Token 仍用 JwtUtil）。
 * 2. 跨服务依赖（按「独立库」处理，一律走 Feign，不再直查他人表）：
 *    - 登录状态判断：原注入 MerchantMapper / 直查 merchants 表 →
 *      MerchantInternalClient 调商家服务内部接口
 *    - 仪表盘统计：原注入 ProductMapper/OrderMapper、后改 DashboardMapper 直查
 *      products/orders/merchants → 分别 Feign 调商品 / 订单 / 商家服务内部接口
 *    - 后台用户列表关键字搜索：原 UserMapper.xml 的 LEFT JOIN customers/merchants →
 *      先由客户/商家服务返回命中 userId 集合，再在 users 表本地 IN 过滤
 * <p>
 * 分层改造（2026-10-05）：本接口 + UserServiceImpl（extends ServiceImpl&lt;UserMapper, User&gt;）标准分层，
 * 实现类可直接使用 MyBatis-Plus 的 IService 便捷方法（list/getById/save/updateById/page/lambdaQuery 等）。
 * 注意：本接口 getById(Long) 为业务方法（返回 UserDto），与 IService.getById(Serializable) 构成重载。
 */
public interface UserService {

    /**
     * 通过用户名，邮箱，手机号查询用户id（任意一个匹配即返回）
     */
    Long selectUserIdByUsernameOrPhoneOrEmail(String username, String phone, String email);

    /**
     * 用户注册
     * @param reg 包含用户名、密码、手机号、邮箱和角色（可选）的请求对象
     * @return 新创建的用户实体
     */
    User register(RegisterRequest reg);

    /**
     * 用户登录（账号）
     * <p>
     * status=0 时的判定逻辑：
     * 1. 查询 merchants/customers 是否有对应数据 → 有数据说明已提交过信息，但 status 仍为 0 → "该账号已禁用"
     * 2. 无数据且 status=0 → 新注册未激活，返回 needsInfo 让前端跳到信息填写页
     * 3. status=1 → 正常登录
     */
    Map<String, Object> login(LoginRequest login);

    /**
     * 用户登录（手机号）
     */
    Map<String, Object> loginByPhone(LoginRequest login);

    /**
     * 用户登录（邮箱）
     */
    Map<String, Object> loginByEmail(LoginRequest login);

    /**
     * 管理员登录（校验角色必须是 admin）
     * 支持用户名/手机号/邮箱任一方式，但角色必须为 admin 才放行
     */
    Map<String, Object> adminLogin(LoginRequest login);

    /**
     * 忘记密码
     */
    void updatePassword(LoginRequest login);

    /**
     * 更新密码
     */
    void updatePasswords(Long id, LoginRequest login);

    /**
     * 重置密码
     */
    void resetPassword(Long id);

    /**
     * 根据用户ID获取用户信息（仅管理员调用，角色校验在 Controller 完成）
     *
     * @param id 目标用户ID
     * @return 用户DTO（不含密码）
     */
    UserDto getById(Long id);

    /**
     * 分页查询用户列表（仅管理员调用，角色校验在 Controller 完成）
     * @param page 分页参数
     * @param role 角色过滤
     * @param status 状态过滤
     * @return 用户DTO分页
     */
    PageResult<UserDto> listUsers(PageRequest page, String role, Integer status);

    /**
     * 获取用户统计数据（类型安全，建议也用 TypeReference）
     */
    Map<String, Long> getUserStats();

    /**
     * 仪表盘全量统计（含趋势数据），TTL 短一点（60s）保证实时性
     * <p>
     * 微服务拆分说明：原实现注入 ProductMapper/OrderMapper 统计商品数/订单数/趋势等。
     * 拆分后改走 DashboardMapper 只读聚合（共享单库）；后续拆独立库改 Feign 调各服务。
     */
    Map<String, Object> getDashboardStats();

    /**
     * 更新用户状态（仅管理员，角色校验在 Controller 完成）
     * @param uId 目标用户ID
     * @param status 目标状态
     */
    void updateStatus(Long uId, Integer status);

    /**
     * 激活用户账号（status 从 0 改为 1）
     * <p>
     * 顾客/商家提交个人信息后调用，将 users.status 从 0（待激活）改为 1（正常）。
     * 由 CustomerService.addCustomer / MerchantService.addMerchant 在插入成功后调用。
     * <p>
     * 微服务拆分说明：原单体中被 MerchantService 跨类调用；拆分后商家服务(shopai-merchant)
     * 激活用户需通过 Feign 调用本服务（后续 P7 实现），本方法作为服务内公共方法保留。
     *
     * @param userId 要激活的用户ID
     */
    void activateUser(Long userId);

    /**
     * 更新用户角色（仅管理员，角色校验在 Controller 完成）
     */
    void updateRole(Long uId, String role);

    /**
     * 商家自行更新账号基础信息（username / phone / email）
     * <p>
     * 仅允许更新当前登录用户自己的信息，管理员无权通过此接口修改他人。
     * 修改后會清除相关缓存，并触发延迟双删。
     * <p>
     * 微服务拆分说明：本方法更新 users 表（归属 shopai-user），原单体中被 UserController
     * 的商户段(/api/user/merchant/updateAccount)调用。拆分后该接口路由到 shopai-merchant，
     * 商家服务需通过 Feign 调用本方法（后续 P7 实现 Feign 客户端）。
     *
     * @param userId 当前登录用户ID（由网关 X-User-Id 头解析）
     */
    void updateAccount(Long userId, String username, String phone, String email);

    /**
     * 销号
     */
    void deleteUser(Long id);

    /**
     * 公开的销号接口（供 Controller 调用）
     * @param currentUserId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @param currentPassword 前端弹窗让用户输入的当前密码
     */
    void deleteAccount(Long currentUserId, String currentPassword);
}
