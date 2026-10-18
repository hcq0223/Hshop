package com.shopai.user.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.PageResult;
import com.shopai.common.PageRequest;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.user.entity.Customer;
import com.shopai.user.mapper.CustomerMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 客户服务实现
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.CustomerService 迁移至 shopai-user 服务。
 * 鉴权改造：原方法通过 token 解析 userId（extractUserId），拆分后由网关写入 X-User-Id 头，
 * Controller 读取后把 userId 显式传入本类，本类不再持有 JwtUtil。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;CustomerMapper, Customer&gt; 实现 CustomerService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class CustomerServiceImpl extends ServiceImpl<CustomerMapper, Customer> implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private UserService userService;
    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    // ==================== 查询（带缓存） ====================

    /**
     * 根据用户ID获取客户Id（单个缓存）
     * @return
     */
    @Override
    public Long UserIdes(Long userId) {
        String key = RedisService.CACHE_CUSTOMER_ID + userId;
        return redisService.getOrSet(key, Long.class, 300L,
                () -> customerMapper.selectIdByUserId(userId));
    }

    /**
     * 根据用户ID获取客户信息（单个缓存）
     */
    @Override
    public Customer getCustomerByUserId(Long userId) {
        String key = RedisService.CACHE_CUSTOMER + userId;
        return redisService.getOrSet(key, Customer.class, 300L,
                () -> customerMapper.selectByUserId(userId));
    }

    /**
     * 1. 客户本人查看自己的详情（从 X-User-Id 头取ID）
     */
    @Override
    public Customer getMyCustomerDetail(Long userId) {
        return getCustomerDetailByUserId(userId); // 调用下面的私有方法
    }

    /**
     * 2. 提供给管理员或内部服务使用的通用查询（传入目标userId）
     * 注意：该方法不包含权限校验，调用方（Controller）需自行加 @PreAuthorize 注解
     */
    @Override
    public Customer getCustomerDetailByUserId(Long userId) {
        String key = RedisService.CACHE_CUSTOMER_DETAIL + userId;
        return redisService.getOrSet(key, Customer.class, 300L,
                () -> customerMapper.selectDetailByUserId(userId));
    }

    /**
     * 根据昵称模糊查询客户列表（列表缓存）
     */
    @Override
    public PageResult<Customer> searchCustomersByNickname(PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        params.put("page", page.getPage());
        params.put("size", page.getSize());
        params.put("nickname", page.getKeyword());
        String hash = RedisService.buildListHash(params);
        String key = RedisService.CACHE_CUSTOMER_LIST + hash;
        // 使用 TypeReference 处理泛型
        return redisService.getOrSetTyped(key, new TypeReference<PageResult<Customer>>() {}, 120L,
                () -> {
                    // 注意：Mapper XML 中已使用 CONCAT('%', #{param}, '%') 做模糊匹配，
                    // 此处传入原始 keyword 即可，不要再手动拼接 %，否则会变成 "%keyword%" 被再包裹一层
                    String kw = page.getKeyword() != null ? page.getKeyword() : "";
                    List<Customer> list = customerMapper.selectByNickname(kw,
                            page.getOffset(), page.getSize());
                    long total = customerMapper.countByNickname(kw);
                    return new PageResult<>(total, page.getPage(), page.getSize(), list);
                });
    }

    // ==================== 写操作 ====================

    /**
     * 新增客户信息（带延迟双删，并激活用户账号）
     * <p>
     * 用户注册时 status=0，填写个人信息后激活为 status=1
     *
     * @param customer 客户信息
     * @param userId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @return
     */
    @Override
    @Transactional
    public int addCustomer(Customer customer, Long userId) {
        customer.setUserId(userId);
        int rows = customerMapper.insert(customer);
        if (rows > 0) {
            evictCustomerCacheAfterCommit(userId);
            // 提交个人信息后激活账号（status: 0 → 1）。
            //
            // 事务安全：改到**事务提交后**执行。原实现在事务内直接调用
            // {@code userService.activateUser(userId)}，而该方法自身是 @Transactional
            // 且会在提交后发 MQ 同步 user 索引 —— 于是出现两个问题：
            //   1) 它可能参与/挂到当前未提交的事务上，激活结果先于客户资料可见；
            //   2) 若外层事务随后回滚（客户资料没落库），用户账号却已被激活、
            //      索引也已同步成「已激活」，状态与数据不一致。
            // 放到提交后调用，使「客户资料落库」成为激活的前提。
            AfterCommitExecutor.run(() -> userService.activateUser(userId));
        }
        return rows;
    }

    /**
     * 更新客户信息（带延迟双删）
     */
    @Override
    @Transactional
    public int updateCustomer(Customer customer, Long userId) {
        Long l = UserIdes(userId);// 触发缓存加载，确保用户ID存在
        if (customer == null || l == null) {
            throw new IllegalArgumentException("客户ID不能为空");
        }

        LambdaUpdateWrapper<Customer> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Customer::getId, l)
                .eq(Customer::getUserId, userId)
                .set(Customer::getNickname, customer.getNickname())
                .set(Customer::getAvatarUrl, customer.getAvatarUrl())
                .set(Customer::getGender, customer.getGender())
                .set(Customer::getDefaultAddressId, customer.getDefaultAddressId());

        int rows = customerMapper.update(null, wrapper);
        if (rows > 0) {
            evictCustomerCacheAfterCommit(userId);
        }
        return rows;
    }

    // ==================== 缓存清理工具 ====================

    /**
     * 事务提交后执行“延迟双删”清理客户缓存
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor}。
     * 原实现直接调用 {@code registerSynchronization}，在**没有事务**时会抛
     * {@code IllegalStateException}；AfterCommitExecutor 在无事务时退化为立即执行，
     * 避免因调用方式变化而丢失缓存清理。
     */
    private void evictCustomerCacheAfterCommit(Long customerId) {
        AfterCommitExecutor.run(() -> {
            // 立即清除缓存
            redisService.clearCustomerCache(customerId);
            redisService.deleteByPrefix(RedisService.CACHE_CUSTOMER_LIST);
            // 延迟再删一次（应对主从延迟或并发读旧数据）
            cacheDelayScheduler.schedule(() -> {
                try {
                    redisService.clearCustomerCache(customerId);
                    log.info("延迟双删成功, customerId={}", customerId);
                } catch (Exception e) {
                    log.error("延迟双删失败, customerId={}, 请手动清理Redis", customerId, e);
                }
            }, 300, TimeUnit.MILLISECONDS);
        });
    }

}
