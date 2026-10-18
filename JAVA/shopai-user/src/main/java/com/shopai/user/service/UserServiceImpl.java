package com.shopai.user.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.PageResult;
import com.shopai.common.PageRequest;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.common.dto.internal.order.DailyOrderStat;
import com.shopai.common.dto.internal.order.MerchantSalesStat;
import com.shopai.common.dto.internal.order.OrderStatusCount;
import com.shopai.common.dto.internal.user.UserIdItem;
import com.shopai.common.mq.IndexChangedEvent;
import com.shopai.common.mq.IndexEventPublisher;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.common.security.JwtUtil;
import com.shopai.user.dto.LoginRequest;
import com.shopai.user.dto.RegisterRequest;
import com.shopai.user.dto.UserDto;
import com.shopai.user.entity.Customer;
import com.shopai.user.entity.User;
import com.shopai.user.feign.MerchantInternalClient;
import com.shopai.user.feign.OrderInternalClient;
import com.shopai.user.feign.ProductInternalClient;
import com.shopai.user.mapper.CustomerMapper;
import com.shopai.user.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现（认证 + 用户管理 + 管理员仪表盘）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.UserService 迁移至 shopai-user 服务。
 * 主要改造点：
 * 1. 鉴权方式：原方法通过 token 解析角色/用户ID（jwtUtil.getRole(token) / getUserId(token)），
 *    拆分后由「网关统一认证」写入 X-User-* 头，Controller 读取头后把 userId/role 显式传入本类，
 *    本类不再持有 JwtUtil 做权限判断（仅登录签发 Token 仍用 JwtUtil）。
 * 2. 跨服务依赖（按「独立库」处理，一律走 Feign，不再直查他人表）：
 *    - 登录状态判断：原直查 merchants 表 → Feign 调商家服务内部接口
 *      （MerchantInternalClient.getByUserId）
 *    - 仪表盘统计：原 DashboardMapper 直查 products/orders/merchants →
 *      Feign 调商品服务（商品总数）、订单服务（订单数/趋势/状态分布/商家销售额）、
 *      商家服务（店铺名批量补齐，替代原 orders JOIN merchants）
 *    - 后台用户列表关键字搜索：原 UserMapper.xml 的 LEFT JOIN customers/merchants →
 *      客户服务/商家服务先查出匹配的 userId 集合，再在 users 表本地 IN 过滤
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;UserMapper, User&gt; 实现 UserService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法（list/getById/save/updateById/page/lambdaQuery 等）；
 * 保留原 @Autowired 字段与方法体。注意 getById(Long) 为业务方法（返回 UserDto），与 IService.getById 构成重载。
 */
@Service("userService")
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class); // 应该是 UserService.class 而非 User.class

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtil jwtUtil;
    // 微服务拆分新增：Feign 客户端（商家 / 订单 / 商品服务内部接口）
    @Autowired
    private MerchantInternalClient merchantInternalClient;
    @Autowired
    private OrderInternalClient orderInternalClient;
    @Autowired
    private ProductInternalClient productInternalClient;
    // 列表查询改造新增：后台用户列表走 ES 索引（替代原 Redis 列表缓存 + deleteByPrefix 全量失效）
    @Autowired
    private UserListIndexService userListIndexService;
    // 列表查询改造新增：用户变更后发 MQ，搜索服务同步 user 索引
    @Autowired
    private IndexEventPublisher indexEventPublisher;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    /**
     * 通过用户名，邮箱，手机号查询用户id（任意一个匹配即返回）
     */
    @Override
    public Long selectUserIdByUsernameOrPhoneOrEmail(String username, String phone, String email) {
        User user = userMapper.selectByUsername(username);
        if (user != null) return user.getId();
        user = userMapper.selectByPhone(phone);
        if (user != null) return user.getId();
        user = userMapper.selectByEmail(email);
        if (user != null) return user.getId();
        return null; // 三个都不存在则返回 null
    }

    /**
     * 用户注册
     * @param reg 包含用户名、密码、手机号、邮箱和角色（可选）的请求对象
     * @return 新创建的用户实体
     */
    @Override
    public User register(RegisterRequest reg) {
        if (userMapper.selectByPhone(reg.getPhone()) != null) throw new RuntimeException("手机号已注册");
        if (userMapper.selectByEmail(reg.getEmail()) != null) throw new RuntimeException("邮箱已注册");
        if (userMapper.selectByUsername(reg.getUsername()) != null) throw new RuntimeException("用户名已存在");
        User user = new User();
        user.setPhone(reg.getPhone());
        user.setEmail(reg.getEmail());
        user.setRole(reg.getRole() == null ? "USER" : reg.getRole());
        user.setPasswordHash(passwordEncoder.encode(reg.getPassword()));
        user.setUsername(reg.getUsername());
        // 注册后 status 设为 0（待激活），填写信息页提交后才改为 1
        user.setStatus(0);
        userMapper.insert(user);

        // 注册后清除用户列表缓存
        redisService.clearUserCache(null);

        return user;
    }

    /**
     * 用户登录（账号）
     * <p>
     * status=0 时的判定逻辑：
     * 1. 查询 merchants/customers 是否有对应数据 → 有数据说明已提交过信息，但 status 仍为 0 → "该账号已禁用"
     * 2. 无数据且 status=0 → 新注册未激活，返回 needsInfo 让前端跳到信息填写页
     * 3. status=1 → 正常登录
     */
    @Override
    public Map<String,Object> login(LoginRequest login){
        User user = userMapper.selectByUsername(login.getUsername());
        if (user == null) throw new RuntimeException("账号不存在");
        if (!passwordEncoder.matches(login.getPassword(),user.getPasswordHash())) throw new RuntimeException("账号或密码错误");

        // 处理 status=0 的情况（新注册默认禁用，待填写信息后激活）
        Map<String, Object> statusCheck = checkAccountStatus(user);
        if (statusCheck != null) return statusCheck;

        return buildLoginResult(user);
    }

    /**
     * 用户登录（手机号）
     */
    @Override
    public Map<String,Object> loginByPhone(LoginRequest login){
        User user = userMapper.selectByPhone(login.getPhone());
        if (user == null) throw new RuntimeException("请填入正确手机号");
        if (!passwordEncoder.matches(login.getPassword(),user.getPasswordHash())) throw new RuntimeException("手机号或密码错误");

        Map<String, Object> statusCheck = checkAccountStatus(user);
        if (statusCheck != null) return statusCheck;

        return buildLoginResult(user);
    }

    /**
     * 用户登录（邮箱）
     */
    @Override
    public Map<String,Object> loginByEmail(LoginRequest login){
        User user = userMapper.selectByEmail(login.getEmail());
        if (user == null) throw new RuntimeException("请填入正确邮箱");
        if (!passwordEncoder.matches(login.getPassword(),user.getPasswordHash())) throw new RuntimeException("邮箱或密码错误");

        Map<String, Object> statusCheck = checkAccountStatus(user);
        if (statusCheck != null) return statusCheck;

        return buildLoginResult(user);
    }

    /**
     * 构建登录成功的返回数据
     */
    private Map<String, Object> buildLoginResult(User user) {
        user.setPasswordHash(null);
        String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getRole());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", user);
        result.put("needsInfo", false);
        return result;
    }

    /**
     * 检查账号状态（status=0 时的细分逻辑）
     * <p>
     * customer 角色 → 查 customers 表；merchant 角色 → 查 merchants 表；admin → 直接报禁用
     * - 有对应数据 → 说明已填过信息但被管理员手动禁用 → 报"该账号已禁用"
     * - 无对应数据 → 新注册未激活 → 返回 token + needsInfo=true，前端跳转信息填写页
     *
     * @return null 表示状态正常（status=1），非 null 表示需要特殊处理
     */
    private Map<String, Object> checkAccountStatus(User user) {
        if (user.getStatus() == 1) return null; // 正常状态

        String role = user.getRole();
        boolean hasInfo = false;

        // 根据角色查询对应的信息表
        if ("customer".equals(role)) {
            hasInfo = customerMapper.selectByUserId(user.getId()) != null;
        } else if ("merchant".equals(role)) {
            // 微服务拆分说明：merchants 表归属 shopai-merchant 服务（独立库），
            // 不再直查该表，改为 Feign 调商家服务内部接口判断是否已提交入驻资料。
            Result<MerchantInfo> r = merchantInternalClient.getByUserId(user.getId());
            hasInfo = r != null && r.getData() != null;
        }

        if (hasInfo) {
            // 已有信息数据但 status=0 → 被管理员手动禁用
            throw new RuntimeException("该账号已禁用");
        }

        // 无信息数据且 status=0 → 新注册，需跳转信息填写页
        user.setPasswordHash(null);
        String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getRole());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", user);
        result.put("needsInfo", true); // 前端据此跳转到信息完善页
        return result;
    }

    /**
     * 管理员登录（校验角色必须是 admin）
     * 支持用户名/手机号/邮箱任一方式，但角色必须为 admin 才放行
     */
    @Override
    public Map<String, Object> adminLogin(LoginRequest login) {
        User user = null;
        if (login.getUsername() != null && !login.getUsername().isBlank()) {
            user = userMapper.selectByUsername(login.getUsername());
        } else if (login.getPhone() != null && !login.getPhone().isBlank()) {
            user = userMapper.selectByPhone(login.getPhone());
        } else if (login.getEmail() != null && !login.getEmail().isBlank()) {
            user = userMapper.selectByEmail(login.getEmail());
        } else {
            throw new RuntimeException("请提供用户名、手机号或邮箱");
        }
        if (user == null) throw new RuntimeException("账号不存在");
        if (!"admin".equals(user.getRole())) throw new RuntimeException("该账号不是管理员，无权登录");
        if (!passwordEncoder.matches(login.getPassword(), user.getPasswordHash())) throw new RuntimeException("密码错误");
        if (user.getStatus() == 0) throw new RuntimeException("账号已被禁用");
        user.setPasswordHash(null);
        String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getRole());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", user);
        return result;
    }

    /**
     * 忘记密码
     */
    @Override
    public void updatePassword(LoginRequest login){
        User user = userMapper.selectByPhone(login.getPhone());
        if (user == null) throw new RuntimeException("请填入正确手机号");
        if (!user.getEmail().equals(login.getEmail())) throw  new RuntimeException("邮箱不匹配");
        if (user.getStatus() == 0) throw new RuntimeException("账号被禁用");
        user.setPasswordHash(passwordEncoder.encode(login.getPassword()));
        userMapper.updateById(user);
        redisService.clearUserCache(user.getId());
    }

    /**
     * 更新密码
     */
    @Override
    public void updatePasswords(Long id, LoginRequest login){
        User user = userMapper.selectByPhone(login.getPhone());
        if (user == null) throw new RuntimeException("请填入正确手机号");
        if (!id.equals(user.getId())) throw new RuntimeException("用户不匹配，手机号有误");
        if (!user.getEmail().equals(login.getEmail())) throw  new RuntimeException("邮箱不匹配");
        if (user.getStatus() == 0) throw new RuntimeException("账号被禁用");
        user.setPasswordHash(passwordEncoder.encode(login.getPassword()));
        userMapper.updateById(user);
        redisService.clearUserCache(user.getId());
    }

    /**
     * 重置密码
     * <p>
     * <b>缓存说明（已评估，刻意保持现状）</b>：
     * 本方法更新的是 {@code password_hash}，而缓存 {@code cache:user:{id}} 里存的是
     * {@link com.shopai.user.dto.UserDto} —— 该 DTO 只有 id/username/phone/email/role/status
     * 等展示字段，**不含任何密码相关字段**。因此重置密码不会让缓存「泄露旧密码」，
     * 也不会让校验逻辑读到旧值（登录/改密都是从数据库读 {@code password_hash}）。
     * <p>
     * 这里仍调用 {@code clearUserCache}，是为了让可能存在的展示字段变更立即生效；
     * 但它属于「顺手清理」而非正确性必需 —— 即便不清，TTL（300 秒，见
     * {@code RedisService.TTL_USER}）也会兜底。
     * 同理，本方法不发送 {@code user.changed} 索引事件：密码不在 user 索引里
     * （见 {@code UserDocument}，索引刻意不含凭据字段），同步索引没有意义。
     */
    @Override
    public void resetPassword(Long id){
        User user = userMapper.selectById(id);
        if (user == null) throw new RuntimeException("用户不存在");
        user.setPasswordHash(passwordEncoder.encode("123456"));
        userMapper.updateById(user);
        redisService.clearUserCache(user.getId());
    }



    /**
     * 根据用户ID获取用户信息（仅管理员调用，角色校验在 Controller 完成）
     *
     * @param id 目标用户ID
     * @return 用户DTO（不含密码）
     */
    @Override
    public UserDto getById(Long id) {
        return redisService.getOrSet(RedisService.CACHE_USER + id, UserDto.class, 300, () -> {
            User u = userMapper.selectById(id);
            if (u == null){
                return null;
            }
            UserDto udto = new UserDto();
            BeanUtils.copyProperties(u, udto);

            return udto;
        });
    }

    /**
     * 分页查询用户列表（仅管理员调用，角色校验在 Controller 完成）
     * @param page 分页参数
     * @param role 角色过滤
     * @param status 状态过滤
     * @return 用户DTO分页
     */
    @Override
    public PageResult<UserDto> listUsers(PageRequest page, String role, Integer status) {
        // 列表查询改造：原来走 Redis 缓存 cache:user:list:{hash} + 跨库 LEFT JOIN
        // customers/merchants 关键字搜索，且任意用户变更 deleteByPrefix 全清。
        // 现改为查 shopai-search 的 user 索引（索引中已冗余客户昵称/店铺名两个检索字段）。
        return userListIndexService.listUsers(page, role, status);
    }

    /**
     * 获取用户统计数据（类型安全，建议也用 TypeReference）
     */
    @Override
    public Map<String, Long> getUserStats() {
        return redisService.getOrSetTyped(
                RedisService.CACHE_USER_STATS,
                new TypeReference<Map<String, Long>>() {}, 120, () -> {
                    Map<String, Long> s = new HashMap<>();
                    s.put("totalUsers", userMapper.countByConditions(null, null, null, null, null));
                    s.put("customerUsers", userMapper.countByRole("customer"));//用户
                    s.put("merchantUsers", userMapper.countByRole("merchant"));//商户
                    s.put("todayNew", userMapper.countTodayNew());
                    return s;
                }
        );
    }

    /**
     * 仪表盘全量统计（含趋势数据），TTL 短一点（60s）保证实时性
     * <p>
     * 微服务拆分说明：原实现注入 ProductMapper/OrderMapper/RMerchantMapper，
     * 之后又改为 DashboardMapper 在「共享单库」阶段直查 products/orders/merchants。
     * 按「独立库」处理后，这三张表分别归属商品 / 订单 / 商家服务，本服务不能再直查，
     * 一律改为 Feign 调用对方内部接口：
     * - 商品总数           → ProductInternalClient.countProducts()
     * - 订单总数/趋势/状态分布/商家销售额 → OrderInternalClient
     * - 商家销售额排名原本靠 {@code orders JOIN merchants} 取店铺名，
     *   现拆成两步：订单服务返回 merchantId+totalSales，再用 MerchantInternalClient
     *   批量补 shopName，在下方组装成与前端约定的 {shopName, totalSales} 结构。
     * <p>
     * 返回结构（键名）与改造前完全一致，前端 AdminDashboard.vue 无需改动。
     */
    @Override
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> result = new HashMap<>();

        // ── 汇总数字 ──
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", userMapper.countByConditions(null, null, null, null, null));
        stats.put("totalMerchants", userMapper.countByRole("merchant"));
        // 商品总数：Feign 调商品服务（原 DashboardMapper.countProducts）
        stats.put("totalProducts", unwrap(productInternalClient.countProducts(), 0L));
        // 订单总数：Feign 调订单服务（原 DashboardMapper.countOrders）
        stats.put("totalOrders", unwrap(orderInternalClient.countOrders(), 0L));
        result.put("stats", stats);

        // ── 近7天每日订单（Feign 调订单服务） ──
        List<DailyOrderStat> dailyOrders = unwrap(orderInternalClient.dailyOrderStats(7), Collections.emptyList());
        result.put("dailyOrders", dailyOrders);

        // ── 订单状态分布（Feign 调订单服务） ──
        List<OrderStatusCount> statusDistribution =
                unwrap(orderInternalClient.countGroupByStatus(), Collections.emptyList());
        result.put("orderStatusDistribution", statusDistribution);

        // ── 近7天每日新增用户（users 表本地聚合，无需跨服务） ──
        result.put("dailyNewUsers", userMapper.dailyNewUsers(7));

        // ── 角色分布（users 表本地聚合，无需跨服务） ──
        result.put("roleDistribution", userMapper.countGroupByRole());

        // ── Top 5 商家销售额（订单聚合 + 商家服务批量补店铺名） ──
        result.put("topMerchants", merchantSalesWithShopName(5, "desc"));

        // ── Bottom 3 商家销售额 ──
        result.put("bottomMerchants", merchantSalesWithShopName(3, "asc"));

        return result;
    }

    // ==================== 跨服务查询辅助（Feign） ====================

    /**
     * 商家销售额排名 + 店铺名组装
     * <p>
     * 替代原 {@code DashboardMapper.top/bottomMerchantsBySales} 中的
     * {@code orders o INNER JOIN merchants m} 跨库联查：
     * 1. Feign 调订单服务，按 orders 本地聚合出 merchantId + totalSales；
     * 2. Feign 调商家服务，批量补齐店铺名；
     * 3. 在 Java 侧组装成前端需要的 {shopName, totalSales}。
     * <p>
     * 降级策略：任一步失败时返回空列表，仪表盘其它区块不受影响（避免跨服务雪崩）。
     *
     * @param limit 取前N条
     * @param order desc=Top / asc=Bottom
     */
    private List<Map<String, Object>> merchantSalesWithShopName(int limit, String order) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<MerchantSalesStat> ranking =
                unwrap(orderInternalClient.merchantSalesRanking(limit, order), Collections.emptyList());
        if (ranking == null || ranking.isEmpty()) {
            return rows;
        }

        // 批量补店铺名（一次 Feign 调用，避免 N+1）
        Map<Long, String> shopNames = new HashMap<>();
        String ids = ranking.stream()
                .map(MerchantSalesStat::getMerchantId)
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (!ids.isEmpty()) {
            List<MerchantBrief> briefs = unwrap(merchantInternalClient.listBriefs(ids), Collections.emptyList());
            if (briefs != null) {
                for (MerchantBrief b : briefs) {
                    if (b.getId() != null) {
                        shopNames.put(b.getId(), b.getShopName());
                    }
                }
            }
        }

        for (MerchantSalesStat stat : ranking) {
            Map<String, Object> row = new LinkedHashMap<>();
            // 键名与前端 AdminDashboard.vue 读取的字段保持一致（原为 SQL 别名）
            row.put("shopName", stat.getMerchantId() == null ? null : shopNames.get(stat.getMerchantId()));
            row.put("totalSales", stat.getTotalSales());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 按客户昵称关键字，向客户服务查询命中的 userId 集合
     * <p>
     * 替代原 UserMapper.xml 的 {@code LEFT JOIN customers c ... c.nickname LIKE}。
     * 客户服务不可用时返回空集合（退化为「仅按用户名/手机/邮箱匹配」）。
     */
    private List<Long> findUserIdsByCustomerNickname(String keyword) {
        try {
            List<Customer> customers = customerMapper.selectByNickname(keyword, 0, 1000);
            List<Long> ids = new ArrayList<>(customers.size());
            for (Customer c : customers) {
                if (c.getUserId() != null) {
                    ids.add(c.getUserId());
                }
            }
            return ids;
        } catch (Exception e) {
            log.error("按客户昵称匹配 userId 失败, keyword={}", keyword, e);
            return Collections.emptyList();
        }
    }

    /**
     * 按店铺名关键字，向商家服务查询命中的 userId 集合
     * <p>
     * 替代原 UserMapper.xml 的 {@code LEFT JOIN merchants m ... m.shop_name LIKE}。
     * 商家服务不可用时返回空集合（退化为「仅按用户名/手机/邮箱匹配」）。
     */
    private List<Long> findUserIdsByShopName(String keyword) {
        try {
            Result<List<UserIdItem>> r = merchantInternalClient.userIdsByShopName(keyword, 1000);
            List<UserIdItem> items = unwrap(r, Collections.emptyList());
            List<Long> ids = new ArrayList<>();
            if (items != null) {
                for (UserIdItem item : items) {
                    if (item.getId() == null) {
                        continue;
                    }
                    try {
                        ids.add(Long.valueOf(item.getId()));
                    } catch (NumberFormatException ignored) {
                        // 忽略非法ID
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.error("按店铺名匹配 userId 失败, keyword={}", keyword, e);
            return Collections.emptyList();
        }
    }

    /**
     * 解包 Feign 的 Result：code=200 且 data 非空时返回 data，否则返回默认值
     * <p>
     * 说明：跨服务调用统一降级——任一依赖服务不可用都不应让管理员接口整体失败。
     */
    private static <T> T unwrap(Result<T> result, T defaultValue) {
        if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
            return defaultValue;
        }
        return result.getData();
    }

    /**
     * 更新用户状态（仅管理员，角色校验在 Controller 完成）
     * @param uId 目标用户ID
     * @param status 目标状态
     */
    @Override
    @Transactional
    public void updateStatus(Long uId, Integer status) {
        int row = userMapper.updateStatus(uId, status);
        if (row <= 0) {
            throw new RuntimeException("用户状态修改失败");
        }
        evictUserCacheAfterCommit(uId);
    }

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
    @Override
    @Transactional
    public void activateUser(Long userId) {
        int row = userMapper.updateStatus(userId, 1);
        if (row <= 0) {
            log.warn("激活用户失败，userId={} 可能不存在", userId);
        } else {
            log.info("用户账号已激活, userId={}", userId);
            evictUserCacheAfterCommit(userId);
        }
    }

    /**
     * 更新用户角色（仅管理员，角色校验在 Controller 完成）
     */
    @Override
    @Transactional
    public void updateRole(Long uId, String role) {
        User user = userMapper.selectById(uId);
        if (user == null) throw new RuntimeException("用户不存在");
        user.setRole(role);
        int row = userMapper.updateById(user);
        if (row <= 0) {
            throw new RuntimeException("用户角色修改失败");
        }
        evictUserCacheAfterCommit(uId);
    }

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
    @Override
    @Transactional
    public void updateAccount(Long userId, String username, String phone, String email) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (username != null && !username.isBlank()) {
            user.setUsername(username.trim());
        }
        if (phone != null && !phone.isBlank()) {
            user.setPhone(phone.trim());
        }
        if (email != null && !email.isBlank()) {
            user.setEmail(email.trim());
        }
        int rows = userMapper.updateById(user);
        if (rows <= 0) {
            throw new RuntimeException("账号信息更新失败");
        }
        evictUserCacheAfterCommit(userId);
        // 同时清除商户缓存（因为 getMerchantById 会补齐 users 信息，缓存中携带了用户信息）
        redisService.deleteKey(RedisService.CACHE_MERCHANT + userId);
        redisService.deleteKey(RedisService.CACHE_MERCHANT_DETAIL + userId);
        // 列表查询改造：账号信息（用户名/手机/邮箱）是 user 索引的检索字段，
        // 变更后必须同步索引，否则后台用户列表按新用户名/手机号搜不到这条用户。
        // 事务安全：放到提交后发（用 AfterCommitExecutor，无事务时立即发）。
        AfterCommitExecutor.run(
                () -> indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.USER, userId));
        log.info("商家 [userId={}] 更新了账号信息", userId);
    }

    /**
     * 销号
     */
    @Override
    @Transactional
    public void deleteUser(Long id) {
        int rows = userMapper.deleteById(id);
        if (rows <= 0) {
            throw new RuntimeException("用户不存在或删除失败");
        }
        // 销号：需从 user 索引删除文档（而非 upsert）
        evictUserCacheAfterCommit(id, null, true);
    }

    /**
     * 公开的销号接口（供 Controller 调用）
     * @param currentUserId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @param currentPassword 前端弹窗让用户输入的当前密码
     */
    @Override
    public void deleteAccount(Long currentUserId, String currentPassword) {
        User user = userMapper.selectById(currentUserId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RuntimeException("当前密码错误，无法执行销号操作");
        }
        this.deleteUser(currentUserId);
    }



    // ==================== 工具方法：缓存失效 + 索引同步 ====================

    /**
     * 用户变更后：清理点缓存并通知搜索服务同步 user / merchant 索引（事务提交后执行）
     * <p>
     * 列表查询改造说明：原实现除了清点缓存，还会 {@code deleteByPrefix(CACHE_USER_LIST)}
     * 把**所有筛选组合、所有页码**的后台用户列表缓存全部清空 —— 这正是本次要消除的重失效。
     * 用户列表已改为查 ES 索引（见 {@link UserListIndexService}），
     * 因此这里不再清理列表缓存，改为发 MQ 让搜索服务增量更新索引。
     * <p>
     * 为什么一并同步 merchant 索引：用户资料（用户名/手机/邮箱）与账号状态会被
     * 商家索引作为「账号正常性」与展示字段使用（原为 {@code JOIN users}），
     * 故用户变更时商家的索引视图也可能过期。
     *
     * @param userId       用户ID
     * @param merchantId   该用户对应的商家ID（可为 null，非商家角色时无此值）
     * @param userDeleted  是否为销号：销号需从 user 索引删除文档，而非 upsert
     */
    private void evictUserCacheAfterCommit(Long userId, Long merchantId, boolean userDeleted) {
        if (userId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            doEvictUserCache(userId, merchantId, userDeleted);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        doEvictUserCache(userId, merchantId, userDeleted);
                    }
                }
        );
    }

    /**
     * 兼容旧签名的便捷重载（不需要同步商家索引、且非销号的场景）
     */
    private void evictUserCacheAfterCommit(Long userId) {
        evictUserCacheAfterCommit(userId, null, false);
    }

    private void doEvictUserCache(Long userId, Long merchantId, boolean userDeleted) {
        // 1. 点缓存清理（仅清除该用户的详情/统计缓存，属低成本失效）
        redisService.clearUserCache(userId);
        // 列表查询改造：原此处 deleteByPrefix(CACHE_USER_LIST) 已移除（列表改查 ES 索引）

        // 2. 索引同步（列表查询改造）
        if (userDeleted) {
            indexEventPublisher.publishDelete(IndexChangedEvent.EntityType.USER, userId);
        } else {
            indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.USER, userId);
        }
        // 用户资料会影响商家索引里的账号信息，一并通知刷新
        if (merchantId != null && !userDeleted) {
            indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.MERCHANT, merchantId);
        }

        // 3. 延迟再清一次点缓存（应对并发读把旧值写回）
        cacheDelayScheduler.schedule(() -> {
            try {
                redisService.clearUserCache(userId);
                log.debug("延迟双删成功, userId={}", userId);
            } catch (Exception e) {
                log.error("延迟双删失败, userId={}, 请手动清理Redis Key", userId, e);
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

}
