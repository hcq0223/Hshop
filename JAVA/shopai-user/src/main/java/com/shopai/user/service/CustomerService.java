package com.shopai.user.service;

import com.shopai.common.PageRequest;
import com.shopai.common.PageResult;
import com.shopai.user.entity.Customer;

/**
 * 客户服务接口
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.CustomerService 迁移至 shopai-user 服务。
 * 鉴权改造：原方法通过 token 解析 userId（extractUserId），拆分后由网关写入 X-User-Id 头，
 * Controller 读取后把 userId 显式传入本类，本类不再持有 JwtUtil。
 * <p>
 * 分层改造（2026-10-05）：本接口 + CustomerServiceImpl（extends ServiceImpl&lt;CustomerMapper, Customer&gt;）标准分层。
 */
public interface CustomerService {

    /**
     * 根据用户ID获取客户Id（单个缓存）
     * @return
     */
    Long UserIdes(Long userId);

    /**
     * 根据用户ID获取客户信息（单个缓存）
     */
    Customer getCustomerByUserId(Long userId);

    /**
     * 1. 客户本人查看自己的详情（从 X-User-Id 头取ID）
     */
    Customer getMyCustomerDetail(Long userId);

    /**
     * 2. 提供给管理员或内部服务使用的通用查询（传入目标userId）
     * 注意：该方法不包含权限校验，调用方（Controller）需自行加 @PreAuthorize 注解
     */
    Customer getCustomerDetailByUserId(Long userId);

    /**
     * 根据昵称模糊查询客户列表（列表缓存）
     */
    PageResult<Customer> searchCustomersByNickname(PageRequest page);

    /**
     * 新增客户信息（带延迟双删，并激活用户账号）
     * <p>
     * 用户注册时 status=0，填写个人信息后激活为 status=1
     *
     * @param customer 客户信息
     * @param userId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @return
     */
    int addCustomer(Customer customer, Long userId);

    /**
     * 更新客户信息（带延迟双删）
     */
    int updateCustomer(Customer customer, Long userId);
}
