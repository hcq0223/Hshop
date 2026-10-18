package com.shopai.merchant.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.entity.MerchantModification;
import com.shopai.merchant.entity.MerchantWeeklyRevenue;
import com.shopai.merchant.entity.MerchantWithdraw;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商家服务接口（入驻/审核/提现/财务/周营收 + 店铺信息修改审核）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.MerchantService 迁移至 shopai-merchant 服务。
 * 主要改造点：
 * 1. 鉴权方式：原方法通过 token 解析 userId/role（extractUserId / jwtUtil.getRole），
 *    拆分后由「网关统一认证」写入 X-User-* 头，Controller 读取后把 userId/role 显式传入本类，
 *    本类不再持有 JwtUtil（管理员校验上移到 Controller）。
 * 2. 跨服务依赖（按「独立库」处理，一律走 Feign，不再直查他人表）：
 *    - users 表（shopai-user）：原 UserService.activateUser / UserRefMapper.updateStatusToActive、
 *      selectByUserId + updateById → UserInternalClient.activateUser / updateAccount / getUser
 *    - orders 表（shopai-order）：原 OrderMapper.countWeeklyCompletedOrders /
 *      OrderRefMapper.countCompletedOrders → OrderInternalClient.countCompletedOrders
 * 3. 商家变更后发 MQ merchant.changed（入驻 create、审核/修改生效 update），搜索服务同步 ES。
 * 4. 原顾客端 LIKE 搜索 searchMerchantsPublic 已迁至 shopai-search（ES），本类不再保留。
 * <p>
 * 分层改造（2026-10-05）：本接口 + MerchantServiceImpl（extends ServiceImpl&lt;MerchantMapper, Merchant&gt;）标准分层。
 */
public interface MerchantService {

    /**
     * 分页查询商户列表（附带商品数量统计）
     * 使用缓存，Key 根据分页参数和关键字生成哈希
     */
    IPage<Merchant> queryMerchantPage(Page<Merchant> page, String keyword);

    /**
     * 新增商户信息
     * <p>
     * 新增时 is_verified 强制设为 0（待审核），忽略前端传入的值。
     * 提交后需管理员审核通过才能使用商家功能。
     * 同时激活用户账号（users.status: 0 → 1）
     * <p>
     * 微服务拆分说明：原 addMerchant(token, merchant) 通过 extractUserId(token) 解析 userId，
     * 拆分后 userId 由网关 X-User-Id 头提供、Controller 显式传入。
     * 激活账号由原 userService.activateUser(userId) 改为 userRefMapper.updateStatusToActive(userId)
     * （users 表归属用户服务，共享单库阶段直写，后续拆库改 Feign）。
     */
    int addMerchant(Long userId, Merchant merchant);

    /**
     * 提交店铺信息修改（不再直接更新 merchants，写入修改申请表）
     * <p>
     * 商家提交修改后，is_verified 不变，商家功能不受影响。
     * 修改内容写入 merchant_modifications 表等待管理员审核。
     */
    void submitModification(Long userId, Merchant merchant);

    /**
     * 提交实名信息修改（写入修改申请表等待审核）
     */
    void submitRealNameModification(Long userId, String realName, String cardId, String cardUrl);

    /**
     * 根据用户ID查询单个商户详情（联查 users 基础信息）
     * <p>
     * 返回的 Merchant 对象中 user 字段包含关联的用户基础信息（username、phone、email 等），
     * 前端可同时展示店铺资料和账号信息。
     * <p>
     * 微服务拆分说明：原 getMerchantById(token) 解析 token 并校验 role==merchant，
     * 拆分后角色校验上移到 Controller（仅 merchant 可调用），此处按 userId 查询。
     */
    Merchant getMerchantById(Long userId);

    /**
     * 根据商户ID查询商户公开信息（客户端使用）
     */
    Merchant getMerchantPublicInfo(Long merchantId);

    /**
     * 查询所有已认证且评分 >= minRating 的商户，按评分降序
     * 使用缓存提升性能（缓存 5 分钟）
     */
    List<Merchant> getVerifiedMerchantsWithMinRating(BigDecimal minRating);

    /**
     * 根据用户ID获取商户详情信息（包含关联的User基础信息，管理员专用）
     * <p>
     * 微服务拆分说明：原 selectDetailByUserId(token, userId) 解析 token 校验 admin，
     * 拆分后 admin 校验上移到 Controller。
     * @param userId
     * @return
     */
    Merchant selectDetailByUserId(Long userId);

    /**
     * 管理员审核商家（通过/拒绝）
     * <p>
     * 将指定商家的 is_verified 字段设为 1（通过）或 0（拒绝）。
     * <p>
     * 微服务拆分说明：原 verifyMerchant(token, merchantId, verified) 解析 token 校验 admin，
     * 拆分后 admin 校验上移到 Controller；审核通过后发 MQ merchant.changed(update) 同步 ES。
     *
     * @param merchantId 商家ID
     * @param verified   1=通过, 0=拒绝
     */
    void verifyMerchant(Long merchantId, Integer verified);

    /**
     * 管理员查询待审核商家列表（is_verified = 0，分页，附带实名信息）
     *
     * @param page 页码
     * @param size 每页条数
     * @return 分页的商家列表，每个 Merchant 的 realName 字段已填充实名认证信息
     */
    IPage<Merchant> getPendingMerchants(Integer page, Integer size);

    /**
     * 查询店铺信息修改申请列表
     *
     * @param status 申请状态（pending / approved / rejected）
     * @return 修改申请列表
     */
    List<MerchantModification> getModifications(String status);

    /**
     * 管理员同意信息修改（支持 store 和 realname 两种类型）
     * <p>
     * 解析 changeDetail JSON，根据 type 分别更新 merchants 表或 merchant_real_name 表。
     */
    void approveModification(Long modId);

    /**
     * 管理员拒绝店铺信息修改
     * <p>
     * 仅将修改申请状态设为 rejected，不修改 merchants 表。
     *
     * @param modId 修改申请ID
     */
    void rejectModification(Long modId);

    /**
     * 根据 userId 查询并返回 merchantId
     * <p>
     * 微服务拆分说明：替代原 getMerchantIdByToken(token)（token 解析 + 查表）。
     * 拆分后 userId 由网关 X-User-Id 头提供。
     */
    Long getMerchantIdByUserId(Long userId);

    /**
     * 商家自行更新账号基础信息（username / phone / email）
     * <p>
     * 仅允许更新当前登录用户自己的 users 表字段，不涉及 merchants 表。
     * <p>
     * 微服务拆分说明：原方法在单体 UserService.updateAccount(token,...)，更新 users 表（归属 shopai-user）。
     * 拆分后该接口路由到 shopai-merchant（路径 /api/merchant/me/account），users 表在「共享单库」阶段
     * 通过 UserRefMapper 直写；后续拆独立库改为 Feign 调用用户服务。
     */
    void updateAccount(Long userId, String username, String phone, String email);

    /**
     * 获取商家资金概览
     */
    Map<String, Object> getFinanceOverview(Long userId);

    /**
     * 申请提现
     */
    void applyWithdraw(Long userId, BigDecimal amount, String remark);

    /**
     * 查询提现记录
     */
    List<MerchantWithdraw> getWithdrawRecords(Long userId);

    /**
     * 管理员查询所有待处理提现
     */
    List<MerchantWithdraw> getPendingWithdraws();

    /**
     * 管理员处理提现（通过/拒绝）
     */
    void handleWithdraw(Long withdrawId, String action);

    /**
     * 每周一 00:00 执行：归档上周营收并重置 total_revenue 为 0
     */
    void archiveWeeklyRevenue();

    /** 查询商家历史周营收记录 */
    List<MerchantWeeklyRevenue> getWeeklyRevenueHistory(Long userId);

    /** 更新商家评分（由 ReviewService 调用）
     *  <p>微服务拆分说明：评分变化同步发 MQ merchant.changed(update)，搜索服务更新 ES 的 merchant 索引。 */
    void updateRating(Long merchantId, BigDecimal rating);

    /**
     * 原子变更商家资金（增量语义，供订单服务内部接口调用）
     * <p>
     * 微服务拆分说明：替代订单服务原先跨库读改写 merchants 资金字段的做法。
     * 除更新金额外还会清理商家详情/公开信息缓存，避免「财务中心」读到旧余额。
     *
     * @param request 资金变动请求（merchantId 必填，各 Delta 字段为增量、null 表示不变动）
     * @return true 表示成功；false 表示商家不存在或请求非法
     */
    boolean changeFunds(com.shopai.common.dto.internal.MerchantFundsChangeRequest request);

    /** 根据 userId 查 Merchant（内部使用，不校验角色） */
    Merchant getByUserId(Long userId);
}
