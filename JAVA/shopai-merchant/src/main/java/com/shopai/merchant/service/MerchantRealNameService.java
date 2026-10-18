package com.shopai.merchant.service;

import com.shopai.merchant.entity.MerchantRealName;

/**
 * 商家实名认证业务层接口
 * <p>
 * 该表不走 Redis 缓存（与登录/注册一致），所有操作直接访问数据库。
 * 微服务拆分说明：从原单体 com.example.demo.service.MerchantRealNameService 迁移至 shopai-merchant 服务。
 * 鉴权改造：原方法通过 token 解析 userId → 查出 merchantId；拆分后 userId 由网关 X-User-Id 头提供、
 * Controller 显式传入，本类不再持有 JwtUtil。
 * <p>
 * 分层改造（2026-10-05）：本接口 + MerchantRealNameServiceImpl（extends ServiceImpl&lt;MerchantRealNameMapper, MerchantRealName&gt;）标准分层。
 */
public interface MerchantRealNameService {

    /**
     * 查询当前商家自己的实名认证信息
     *
     * @param userId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @return 实名信息实体，若未提交则返回 null
     */
    MerchantRealName getMyRealName(Long userId);

    /**
     * 新增（提交）实名认证信息
     * <p>
     * 由于 merchant_real_name 表有 uk_merchant_id 唯一约束，
     * 同一商家只能存在一条记录。业务上不允许重复提交。
     *
     * @param userId   当前登录用户ID（由网关 X-User-Id 头解析）
     * @param realName 前端传入的实名信息（仅使用 realName / cardId / cardUrl 字段）
     * @return 影响行数
     */
    int addRealName(Long userId, MerchantRealName realName);

    /**
     * 更新实名认证信息
     * <p>
     * 仅允许修改自己名下的实名记录，通过 userId 校验归属。
     *
     * @param userId   当前登录用户ID（由网关 X-User-Id 头解析）
     * @param realName 前端传入的实名信息（使用 realName / cardId / cardUrl 作为更新值）
     * @return 影响行数
     */
    int updateRealName(Long userId, MerchantRealName realName);

    /**
     * 删除实名认证信息
     * <p>
     * 仅允许删除自己名下的记录，通过 userId 校验归属。
     *
     * @param userId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @return 影响行数
     */
    int deleteRealName(Long userId);
}
