package com.shopai.order.service;

import com.shopai.order.entity.Address;

import java.util.List;

/**
 * 地址管理服务接口
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.AddressService 迁移至 shopai-order 服务。
 * 原类持有的 JwtUtil 依赖（用于从 token 获取用户）已移除，
 * 拆分后由 Controller 从 X-User-Id 头解析 userId 并解析 customerId 后显式传入。
 * <p>
 * 分层改造（2026-10-05）：本接口 + AddressServiceImpl（extends ServiceImpl&lt;AddressMapper, Address&gt;）标准分层。
 */
public interface AddressService {

    /**
     * 获取某个客户的所有地址（列表缓存，默认地址排最前）
     */
    List<Address> getAddressesByCustomerId(Long customerId);

    /**
     * 获取单个地址详情（单个缓存）
     */
    Address getAddressById(Long id);

    /**
     * 新增地址
     */
    void addAddress(Address address);

    /**
     * 修改地址信息（仅允许修改接收人、电话、详细地址等，不允许修改 customer_id）
     */
    void updateAddress(Address address);

    /**
     * 删除地址（物理删除）
     */
    void deleteAddress(Long id);

    /**
     * 设置默认地址（事务：先清其他默认，再设当前为默认）
     */
    void setDefaultAddress(Long id, Long customerId);
}
