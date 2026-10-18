package com.shopai.order.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.order.entity.Address;
import com.shopai.order.mapper.AddressMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 地址管理服务实现
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.AddressService 迁移至 shopai-order 服务。
 * 原类持有的 JwtUtil 依赖（用于从 token 获取用户）已移除，
 * 拆分后由 Controller 从 X-User-Id 头解析 userId 并解析 customerId 后显式传入。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;AddressMapper, Address&gt; 实现 AddressService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements AddressService {


    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    // ==================== 查询（带缓存） ====================

    /**
     * 获取某个客户的所有地址（列表缓存，默认地址排最前）
     */
    @Override
    public List<Address> getAddressesByCustomerId(Long customerId) {
        String key = RedisService.CACHE_ADDRESS_LIST + customerId;
        // 使用泛型缓存读取
        List<Address> cached = redisService.getOrSetTyped(
                key,
                new TypeReference<List<Address>>() {},
                300L, // 5分钟缓存
                () -> {
                    List<Address> list = addressMapper.selectByCustomerId(customerId);
                    return list == null ? Collections.emptyList() : list;
                }
        );
        return cached == null ? Collections.emptyList() : cached;
    }

    /**
     * 获取单个地址详情（单个缓存）
     */
    @Override
    public Address getAddressById(Long id) {
        String key = RedisService.CACHE_ADDRESS + id;
        return redisService.getOrSet(key, Address.class, 300L,
                () -> addressMapper.selectById(id)
        );
    }

    /**
     * 事务提交后清理地址相关缓存
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor}。
     * 原实现直接调用 {@code registerSynchronization}，在**没有事务**时会抛
     * {@code IllegalStateException}；AfterCommitExecutor 在无事务时退化为立即执行。
     */
    private void evictCacheAfterCommit(Long addressId, Long customerId) {
        AfterCommitExecutor.run(() -> {
            if (addressId != null) {
                redisService.deleteKey(RedisService.CACHE_ADDRESS + addressId);
            }
            if (customerId != null) {
                redisService.deleteKey(RedisService.CACHE_ADDRESS_LIST + customerId);
            }
        });
    }

    // ==================== 写操作（事务 + 缓存清理） ====================

    /**
     * 新增地址
     */
    @Override
    @Transactional
    public void addAddress(Address address) {
        // 如果新增的是默认地址，需先将该客户其他地址设为非默认
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            addressMapper.clearDefaultByCustomerId(address.getCustomerId());
        }
        addressMapper.insert(address);
        evictCacheAfterCommit(null, address.getCustomerId());
    }

    /**
     * 修改地址信息（仅允许修改接收人、电话、详细地址等，不允许修改 customer_id）
     */
    @Override
    @Transactional
    public void updateAddress(Address address) {
        // 不允许修改 customer_id，业务上需保证传入的 address 不含 customer_id 变更
        // 先查询旧地址，用于清理缓存
        Address old = addressMapper.selectById(address.getId());
        if (old == null) {
            throw new RuntimeException("地址不存在");
        }
        // 如果修改后变为默认地址，则清除该客户其他默认
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            addressMapper.clearDefaultByCustomerId(old.getCustomerId());
        }
        addressMapper.updateById(address);
        // 清理缓存：单个地址 + 该客户的列表缓存
        evictCacheAfterCommit(address.getId(), old.getCustomerId());
    }

    /**
     * 删除地址（物理删除）
     */
    @Override
    @Transactional
    public void deleteAddress(Long id) {
        Address address = addressMapper.selectById(id);
        if (address == null) {
            throw new RuntimeException("地址不存在");
        }
        addressMapper.deleteById(id);
        // 清理缓存
        evictCacheAfterCommit(id, address.getCustomerId());
    }

    /**
     * 设置默认地址（事务：先清其他默认，再设当前为默认）
     */
    @Override
    @Transactional
    public void setDefaultAddress(Long id, Long customerId) {
        // 1. 清空该客户所有默认标记
        addressMapper.clearDefaultByCustomerId(customerId);
        // 2. 设置当前地址为默认
        int rows = addressMapper.setDefault(id, customerId);
        if (rows == 0) {
            throw new RuntimeException("设置默认地址失败，可能地址不属于该客户或已删除");
        }
        // 3. 清理缓存
        evictCacheAfterCommit(id, customerId);
    }

    // ==================== 缓存清理辅助方法 ====================

    private void clearListCache(Long customerId) {
        redisService.deleteKey(RedisService.CACHE_ADDRESS_LIST + customerId);
    }

    private void clearSingleCache(Long addressId) {
        redisService.deleteKey(RedisService.CACHE_ADDRESS + addressId);
    }
}
