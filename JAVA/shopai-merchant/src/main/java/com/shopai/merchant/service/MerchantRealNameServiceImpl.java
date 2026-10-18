package com.shopai.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.merchant.entity.MerchantRealName;
import com.shopai.merchant.mapper.MerchantMapper;
import com.shopai.merchant.mapper.MerchantRealNameMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家实名认证业务层实现
 * <p>
 * 该表不走 Redis 缓存（与登录/注册一致），所有操作直接访问数据库。
 * 微服务拆分说明：从原单体 com.example.demo.service.MerchantRealNameService 迁移至 shopai-merchant 服务。
 * 鉴权改造：原方法通过 token 解析 userId → 查出 merchantId；拆分后 userId 由网关 X-User-Id 头提供、
 * Controller 显式传入，本类不再持有 JwtUtil。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;MerchantRealNameMapper, MerchantRealName&gt; 实现 MerchantRealNameService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原构造注入字段与方法体。
 */
@Service
@RequiredArgsConstructor
public class MerchantRealNameServiceImpl extends ServiceImpl<MerchantRealNameMapper, MerchantRealName> implements MerchantRealNameService {

    private final MerchantRealNameMapper realNameMapper;
    private final MerchantMapper merchantMapper;

    private static final Logger log = LoggerFactory.getLogger(MerchantRealNameService.class);

    /**
     * 从 userId 中提取当前登录用户的 merchantId
     * <p>
     * 流程：userId → merchants 表查询 → merchantId
     * <p>
     * 微服务拆分说明：原 extractMerchantId(token) 用 jwtUtil.getUserId(token)，
     * 拆分后 userId 由网关 X-User-Id 头提供。
     */
    private Long extractMerchantId(Long userId) {
        // 从 merchants 表根据 userId 查出 merchantId
        var merchant = merchantMapper.selectByUserId(userId);
        if (merchant == null) {
            throw new RuntimeException("当前用户未关联商家信息，请先完成商家入驻");
        }
        return merchant.getId();
    }

    // ==================== 查询 ====================

    /**
     * 查询当前商家自己的实名认证信息
     *
     * @param userId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @return 实名信息实体，若未提交则返回 null
     */
    @Override
    public MerchantRealName getMyRealName(Long userId) {
        Long merchantId = extractMerchantId(userId);
        LambdaQueryWrapper<MerchantRealName> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MerchantRealName::getMerchantId, merchantId);
        return realNameMapper.selectOne(wrapper);
    }

    // ==================== 新增 ====================

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
    @Override
    @Transactional
    public int addRealName(Long userId, MerchantRealName realName) {
        Long merchantId = extractMerchantId(userId);

        // 检查是否已存在实名记录
        LambdaQueryWrapper<MerchantRealName> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MerchantRealName::getMerchantId, merchantId);
        if (realNameMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("已提交过实名认证，无需重复提交，可直接修改");
        }

        // 校验必填字段
        if (realName.getRealName() == null || realName.getRealName().isBlank()) {
            throw new RuntimeException("真实姓名不能为空");
        }
        if (realName.getCardId() == null || realName.getCardId().isBlank()) {
            throw new RuntimeException("身份证号码不能为空");
        }

        realName.setMerchantId(merchantId);
        int rows = realNameMapper.insert(realName);
        log.info("商家 [merchantId={}] 提交了实名认证信息", merchantId);
        return rows;
    }

    // ==================== 更新 ====================

    /**
     * 更新实名认证信息
     * <p>
     * 仅允许修改自己名下的实名记录，通过 userId 校验归属。
     *
     * @param userId   当前登录用户ID（由网关 X-User-Id 头解析）
     * @param realName 前端传入的实名信息（使用 realName / cardId / cardUrl 作为更新值）
     * @return 影响行数
     */
    @Override
    @Transactional
    public int updateRealName(Long userId, MerchantRealName realName) {
        Long merchantId = extractMerchantId(userId);

        // 校验必填字段
        if (realName.getRealName() == null || realName.getRealName().isBlank()) {
            throw new RuntimeException("真实姓名不能为空");
        }
        if (realName.getCardId() == null || realName.getCardId().isBlank()) {
            throw new RuntimeException("身份证号码不能为空");
        }

        LambdaQueryWrapper<MerchantRealName> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MerchantRealName::getMerchantId, merchantId);

        MerchantRealName exist = realNameMapper.selectOne(wrapper);
        if (exist == null) {
            throw new RuntimeException("尚未提交实名认证，请先新增");
        }

        // 只更新允许修改的字段
        exist.setRealName(realName.getRealName());
        exist.setCardId(realName.getCardId());
        if (realName.getCardUrl() != null) {
            exist.setCardUrl(realName.getCardUrl());
        }

        int rows = realNameMapper.updateById(exist);
        log.info("商家 [merchantId={}] 更新了实名认证信息", merchantId);
        return rows;
    }

    // ==================== 删除 ====================

    /**
     * 删除实名认证信息
     * <p>
     * 仅允许删除自己名下的记录，通过 userId 校验归属。
     *
     * @param userId 当前登录用户ID（由网关 X-User-Id 头解析）
     * @return 影响行数
     */
    @Override
    @Transactional
    public int deleteRealName(Long userId) {
        Long merchantId = extractMerchantId(userId);

        LambdaQueryWrapper<MerchantRealName> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MerchantRealName::getMerchantId, merchantId);

        int rows = realNameMapper.delete(wrapper);
        log.info("商家 [merchantId={}] 删除了实名认证信息", merchantId);
        return rows;
    }
}
