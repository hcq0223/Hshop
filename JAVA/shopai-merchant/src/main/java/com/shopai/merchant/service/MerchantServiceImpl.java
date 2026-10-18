package com.shopai.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.dto.internal.MerchantFundsChangeRequest;
import com.shopai.common.redis.RedisService;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.entity.MerchantModification;
import com.shopai.merchant.entity.MerchantRealName;
import com.shopai.merchant.entity.MerchantWeeklyRevenue;
import com.shopai.merchant.entity.MerchantWithdraw;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.UserInfo;
import com.shopai.common.mq.IndexChangedEvent;
import com.shopai.common.mq.IndexEventPublisher;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.merchant.entity.UserRef;
import com.shopai.merchant.feign.OrderInternalClient;
import com.shopai.merchant.feign.UserInternalClient;
import com.shopai.merchant.mapper.MerchantMapper;
import com.shopai.merchant.mapper.MerchantModificationMapper;
import com.shopai.merchant.mapper.MerchantRealNameMapper;
import com.shopai.merchant.mapper.MerchantWeeklyRevenueMapper;
import com.shopai.merchant.mapper.MerchantWithdrawMapper;
import com.shopai.merchant.mq.MerchantEventPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 商家服务实现（入驻/审核/提现/财务/周营收 + 店铺信息修改审核）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.MerchantService 迁移至 shopai-merchant 服务。
 * 主要改造点：
 * 1. 鉴权方式：原方法通过 token 解析 userId/role（extractUserId / jwtUtil.getRole），
 *    拆分后由「网关统一认证」写入 X-User-* 头，Controller 读取后把 userId/role 显式传入本类，
 *    本类不再持有 JwtUtil（管理员校验上移到 Controller）。
 * 2. 跨服务依赖（按「独立库」处理，一律走 Feign，不再直查他人表）：
 *    - users 表（shopai-user）：原 UserRefMapper.updateStatusToActive / selectByUserId + updateById
 *      → UserInternalClient.activateUser / updateAccount / getUser
 *    - orders 表（shopai-order）：原 OrderRefMapper.countCompletedOrders
 *      → OrderInternalClient.countCompletedOrders
 * 3. 商家变更后发 MQ merchant.changed（入驻 create、审核/修改生效 update），搜索服务同步 ES。
 * 4. 原顾客端 LIKE 搜索 searchMerchantsPublic 已迁至 shopai-search（ES），本类不再保留。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;MerchantMapper, Merchant&gt; 实现 MerchantService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements MerchantService {
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private MerchantRealNameMapper realNameMapper;
    @Autowired
    private MerchantModificationMapper modMapper;
    @Autowired
    private MerchantWithdrawMapper withdrawMapper;
    @Autowired
    private MerchantWeeklyRevenueMapper weeklyRevenueMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private MerchantEventPublisher merchantEventPublisher;
    // 微服务拆分新增：Feign 客户端（用户服务 users 表、订单服务 orders 表内部接口）
    @Autowired
    private UserInternalClient userInternalClient;
    @Autowired
    private OrderInternalClient orderInternalClient;
    // 列表查询改造新增：列表走 ES 索引（替代原 Redis 分页缓存 + deleteByPrefix 全量失效）
    @Autowired
    private MerchantListIndexService merchantListIndexService;
    // 列表查询改造新增：商家变更后发 MQ，搜索服务同步 merchant 索引
    @Autowired
    private IndexEventPublisher indexEventPublisher;
    // 资金字段变更后的缓存失效（三个 key 统一收口，避免各入口漏清）
    @Autowired
    private MerchantFinanceCacheEvictor merchantFinanceCacheEvictor;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    private static final Logger log = LoggerFactory.getLogger(MerchantService.class); // 应该是 MerchantService.class 而非 Merchant.class
    // 缓存过期时间：视业务对实时性要求，建议 5~10 分钟
    private static final long CACHE_TTL_SECONDS = 300;

    /**
     * 分页查询商户列表
     * 直接查 shopai-search 的 merchant 索引：按评分倒序、支持店铺名关键字检索、
     * ES 原生分页，不再需要「缓存 + 全量失效」。
     */
    @Override
    public IPage<Merchant> queryMerchantPage(Page<Merchant> page, String keyword) {
        return merchantListIndexService.queryMerchantPage(page, keyword);
    }

    /**
     * 新增商户信息
     * <p>
     * 新增时 is_verified 强制设为 0（待审核），忽略前端传入的值。
     * 提交后需管理员审核通过才能使用商家功能。
     * 同时激活用户账号（users.status: 0 → 1）
     * <p>
     * 微服务拆分说明：原 addMerchant(token, merchant) 通过 extractUserId(token) 解析 userId，
     * 拆分后 userId 由网关 X-User-Id 头提供、Controller 显式传入。
     * 激活账号由原 userService.activateUser(userId) 改为 Feign 调用户服务内部接口
     * （users 表归属用户服务，独立库后不能直写）。
     */
    @Override
    @Transactional
    public int addMerchant(Long userId, Merchant merchant) {
        merchant.setUserId(userId);
        // 新入驻商家 is_verified 固定为 0，忽略前端传入的值
        merchant.setIsVerified(0);
        // rating 初始 5.00，商家不可自行修改
        merchant.setRating(new BigDecimal("5.00"));
        int rows = merchantMapper.insert(merchant);
        if (rows > 0) {
            evictMerchantCacheAfterCommit(userId);

            // 提交入驻信息后激活账号（status: 0 → 1）—— Feign 调用户服务。
            //
            // 事务安全：改到**事务提交后**执行。原实现在事务内直接调用，而该 Feign 调用
            // 会在用户服务端立即提交并同步 user 索引；若本事务随后回滚（商家资料没落库），
            // 用户账号却已被激活 —— 与 CustomerServiceImpl.addCustomer 是同一类问题。
            // 放到提交后调用，使「商家资料落库」成为激活的前提。
            AfterCommitExecutor.run(() -> activateUserViaFeign(userId));

            // 事务提交后发 MQ（create），搜索服务同步 ES
            // 事务安全：用 AfterCommitExecutor 统一「提交后执行」语义（无事务时立即发）
            Long merchantId = merchant.getId();
            AfterCommitExecutor.run(
                    () -> merchantEventPublisher.publish("create", merchantId));
        }
        return rows;
    }

    /**
     * 通过 Feign 激活用户账号（users.status: 0 → 1）
     * <p>
     * 微服务拆分说明：原 {@code UserRefMapper.updateStatusToActive(userId)} 跨库直写 users 表，
     * 独立库后改为调用户服务内部接口 POST /api/user/internal/{userId}/activate。
     * <p>
     * 失败处理：激活失败不阻断入驻流程（商家资料已落库），仅记录告警，
     * 与改造前「仅记录 warns」的行为保持一致。
     */
    private void activateUserViaFeign(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            Result<Integer> r = userInternalClient.activateUser(userId);
            if (r == null || r.getCode() == null || r.getCode() != 200) {
                log.warn("激活用户失败, userId={}, resp={}", userId, r == null ? null : r.getMessage());
            }
        } catch (Exception e) {
            log.error("调用用户服务激活账号失败, userId={}", userId, e);
        }
    }

    /**
     * 提交店铺信息修改（不再直接更新 merchants，写入修改申请表）
     * <p>
     * 商家提交修改后，is_verified 不变，商家功能不受影响。
     * 修改内容写入 merchant_modifications 表等待管理员审核。
     */
    @Override
    @Transactional
    public void submitModification(Long userId, Merchant merchant) {
        Merchant original = merchantMapper.selectByUserId(userId);
        if (original == null) {
            throw new RuntimeException("商户信息不存在");
        }

        // 对比变化，只记录有变更的字段
        Map<String, String> changes = new java.util.LinkedHashMap<>();
        if (merchant.getShopName() != null && !merchant.getShopName().isBlank()
                && !merchant.getShopName().equals(original.getShopName())) {
            changes.put("shopName", merchant.getShopName());
        }
        if (merchant.getContactName() != null && !merchant.getContactName().isBlank()
                && !merchant.getContactName().equals(original.getContactName())) {
            changes.put("contactName", merchant.getContactName());
        }
        if (merchant.getContactPhone() != null && !merchant.getContactPhone().isBlank()
                && !merchant.getContactPhone().equals(original.getContactPhone())) {
            changes.put("contactPhone", merchant.getContactPhone());
        }
        if (merchant.getLogoUrl() != null && !merchant.getLogoUrl().isBlank()
                && !merchant.getLogoUrl().equals(original.getLogoUrl())) {
            changes.put("logoUrl", merchant.getLogoUrl());
        }
        if (merchant.getBusinessLicense() != null && !merchant.getBusinessLicense().isBlank()
                && !merchant.getBusinessLicense().equals(original.getBusinessLicense())) {
            changes.put("businessLicense", merchant.getBusinessLicense());
        }
        if (merchant.getShopDescription() != null
                && !merchant.getShopDescription().equals(
                        original.getShopDescription() == null ? "" : original.getShopDescription())) {
            changes.put("shopDescription", merchant.getShopDescription());
        }
        if (merchant.getBanners() != null) {
            String origBanners = original.getBanners() == null ? "" : original.getBanners();
            if (!merchant.getBanners().equals(origBanners)) {
                changes.put("banners", merchant.getBanners());
            }
        }
        if (changes.isEmpty()) {
            throw new RuntimeException("没有需要修改的内容");
        }

        // 使用 Jackson 序列化变更内容
        String changeDetail;
        try {
            changeDetail = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(changes);
        } catch (Exception e) {
            throw new RuntimeException("序列化修改内容失败", e);
        }

        MerchantModification mod = new MerchantModification();
        mod.setMerchantId(original.getId());
        mod.setShopName(original.getShopName() != null ? original.getShopName() : "未知店铺");
        mod.setType("store");
        mod.setChangeDetail(changeDetail);
        mod.setStatus("pending");
        mod.setCreatedAt(java.time.LocalDateTime.now());
        modMapper.insert(mod);

        // 清除缓存
        evictMerchantCacheAfterCommit(userId);
    }

    /**
     * 提交实名信息修改（写入修改申请表等待审核）
     */
    @Override
    @Transactional
    public void submitRealNameModification(Long userId, String realName, String cardId, String cardUrl) {
        Merchant original = merchantMapper.selectDetailByUserId(userId);
        if (original == null) {
            throw new RuntimeException("商户信息不存在");
        }

        // 对比变化
        Map<String, String> changes = new java.util.LinkedHashMap<>();
        if (realName != null && !realName.isBlank()) {
            changes.put("realName", realName);
        }
        if (cardId != null && !cardId.isBlank()) {
            changes.put("cardId", cardId);
        }
        if (cardUrl != null && !cardUrl.isBlank()) {
            changes.put("cardUrl", cardUrl);
        }
        if (changes.isEmpty()) {
            throw new RuntimeException("没有需要修改的内容");
        }

        String changeDetail;
        try {
            changeDetail = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(changes);
        } catch (Exception e) {
            throw new RuntimeException("序列化修改内容失败", e);
        }

        MerchantModification mod = new MerchantModification();
        mod.setMerchantId(original.getId());
        mod.setShopName(original.getShopName() != null ? original.getShopName() : "未知店铺");
        mod.setType("realname");
        mod.setChangeDetail(changeDetail);
        mod.setStatus("pending");
        mod.setCreatedAt(java.time.LocalDateTime.now());
        modMapper.insert(mod);

        evictMerchantCacheAfterCommit(userId);
    }




    /**
     * 根据用户ID查询单个商户详情（联查 users 基础信息）
     * <p>
     * 返回的 Merchant 对象中 user 字段包含关联的用户基础信息（username、phone、email 等），
     * 前端可同时展示店铺资料和账号信息。
     * <p>
     * 微服务拆分说明：原 getMerchantById(token) 解析 token 并校验 role==merchant，
     * 拆分后角色校验上移到 Controller（仅 merchant 可调用），此处按 userId 查询。
     */
    @Override
    public Merchant getMerchantById(Long userId) {
        // 本地查商户 + 本服务实名信息；账号信息由 Feign 调用户服务补齐（原 SQL 为 JOIN users）
        String cacheKey = RedisService.CACHE_MERCHANT + userId;

        return redisService.getOrSet(cacheKey, Merchant.class, CACHE_TTL_SECONDS,
                () -> {
                    Merchant merchant = merchantMapper.selectDetailByUserId(userId);
                    if (merchant != null) {
                        attachUserInfo(merchant);
                        return merchant;
                    }
                    return null;
                });
    }

    /**
     * 根据商户ID查询商户公开信息（客户端使用）
     * <p>
     * 微服务拆分说明：原 SQL 用 {@code INNER JOIN users u ... AND u.status = 1} 过滤「账号正常」。
     * 独立库后改为：本服务查商户，再 Feign 调用户服务校验账号状态，
     * 账号被禁用时视为不可见（返回 null），保持与原 JOIN 一致的对外语义。
     */
    @Override
    public Merchant getMerchantPublicInfo(Long merchantId) {
        // 构建缓存 Key（利用 RedisService 的 buildDetailHash 方法）
        String cacheKey = RedisService.CACHE_MERCHANT_PUBLIC + merchantId;

        return redisService.getOrSet(cacheKey, Merchant.class, CACHE_TTL_SECONDS,
                () -> {
                    Merchant merchant = merchantMapper.selectPublicInfoById(merchantId);
                    if (merchant == null) {
                        return null;
                    }
                    // 账号状态校验（原 INNER JOIN users ... u.status = 1 的等价逻辑）
                    if (!isUserActive(merchant.getUserId())) {
                        log.info("商户 [{}] 关联账号不可用，公开信息不展示", merchantId);
                        return null;
                    }
                    return merchant;
                });
    }

    /**
     * 查询所有已认证且评分 >= minRating 的商户，按评分降序
     * 使用缓存提升性能（缓存 5 分钟）
     */
    @Override
    public List<Merchant> getVerifiedMerchantsWithMinRating(BigDecimal minRating) {
        // 构造缓存 Key，例如：cache:merchant:verified:4.5
        String cacheKey = RedisService.CACHE_MERCHANT + "verified:" + minRating.stripTrailingZeros().toPlainString();

        // 利用 RedisService 的泛型缓存方法，实现缓存穿透保护
        return redisService.getOrSetTyped(
                cacheKey,
                new TypeReference<List<Merchant>>() {},
                300L, // 缓存 300 秒
                () -> {
                    // 缓存不存在时，从数据库查询
                    List<Merchant> list = merchantMapper.selectVerifiedWithMinRating(minRating);
                    // 如果结果为空，RedisService 会自动存储 "__NULL__" 防止缓存穿透
                    return list != null ? list : Collections.emptyList();
                }
        );
    }



    /**
     * 根据用户ID获取商户详情信息（包含关联的User基础信息，管理员专用）
     * <p>
     * 微服务拆分说明：原 selectDetailByUserId(token, userId) 解析 token 校验 admin，
     * 拆分后 admin 校验上移到 Controller。
     * <p>
     * 微服务拆分说明（独立库）：原 SQL {@code INNER JOIN users u} 取账号信息，
     * 现改为本地查商户 + Feign 调用户服务补齐 {@code Merchant.user}。
     * @param userId
     * @return
     */
    @Override
    public Merchant selectDetailByUserId(Long userId) {
        return redisService.getOrSet(RedisService.CACHE_MERCHANT_DETAIL+userId, Merchant.class, CACHE_TTL_SECONDS, () -> {
            Merchant merchant = merchantMapper.selectDetailByUserId(userId);
            if (merchant != null) {
                attachUserInfo(merchant);
                return merchant;
            }
            return null;
        });
    }

    // ==================== 跨服务：用户信息补齐（Feign 替代 JOIN users） ====================

    /**
     * 为商户挂载关联的用户基础信息（username / phone / email / status 等）
     * <p>
     * 微服务拆分说明：替代原 {@code MerchantMapper.xml} 的 {@code INNER JOIN users u}
     * 以及 {@code UserRefMapper.selectByUserId}。users 表归属 shopai-user 服务，
     * 按「独立库」处理后改为 Feign 调其内部接口。
     * <p>
     * 降级策略：用户服务不可用时保持 {@code merchant.user} 为 null，
     * 商户自身的店铺资料照常返回。
     */
    private void attachUserInfo(Merchant merchant) {
        if (merchant == null || merchant.getUserId() == null) {
            return;
        }
        try {
            Result<UserInfo> r = userInternalClient.getUser(merchant.getUserId());
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                merchant.setUser(toUserRef(r.getData()));
            }
        } catch (Exception e) {
            log.error("调用用户服务查询用户信息失败, userId={}", merchant.getUserId(), e);
        }
    }

    /**
     * 判断商户关联的用户账号是否正常（status = 1）
     * <p>
     * 微服务拆分说明：替代原 {@code MerchantMapper.xml} 的 {@code INNER JOIN users u AND u.status = 1}。
     * <p>
     * 降级策略：用户服务不可用时返回 true（宁可展示也不误隐藏店铺），并记录错误日志。
     */
    private boolean isUserActive(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            Result<UserInfo> r = userInternalClient.getUser(userId);
            if (r != null && r.getCode() != null && r.getCode() == 200) {
                UserInfo info = r.getData();
                // 用户不存在 → 视为不可用（与原 INNER JOIN 无匹配行等价）
                return info != null && info.getStatus() != null && info.getStatus() == 1;
            }
        } catch (Exception e) {
            log.error("调用用户服务校验账号状态失败（按可用处理）, userId={}", userId, e);
            return true;
        }
        return true;
    }

    /** UserInfo（跨服务 DTO）→ 商家服务展示用的 UserRef */
    private UserRef toUserRef(UserInfo info) {
        UserRef ref = new UserRef();
        ref.setId(info.getId());
        ref.setUsername(info.getUsername());
        ref.setPhone(info.getPhone());
        ref.setEmail(info.getEmail());
        ref.setRole(info.getRole());
        ref.setStatus(info.getStatus());
        ref.setCreateTime(info.getCreateTime());
        ref.setUpdateTime(info.getUpdateTime());
        return ref;
    }
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
    @Override
    @Transactional
    public void verifyMerchant(Long merchantId, Integer verified) {
        LambdaUpdateWrapper<Merchant> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Merchant::getId, merchantId)
                .set(Merchant::getIsVerified, verified);
        int rows = merchantMapper.update(null, wrapper);
        if (rows == 0) {
            throw new RuntimeException("商家不存在或审核状态未变更");
        }

        // 事务提交后清缓存 + 发 MQ（搜索服务同步 ES）
        // 事务安全：用 AfterCommitExecutor 统一「提交后执行」语义（无事务时立即执行）
        AfterCommitExecutor.run(
                () -> {
                    // 清除相关缓存（列表查询改造：不再清商家分页列表缓存——列表已改查 ES 索引，
                    // 索引的实时性由下面发的 MQ 事件保证；此处只清「单商家」相关缓存）
                    redisService.deleteKey(RedisService.CACHE_MERCHANT_PUBLIC + merchantId);
                },
                () -> {
                    // 审核通过时发 MQ 商家变更事件，同步 ES 索引
                    // （含审核未通过的情况：索引侧会按 is_verified 判断是否移出索引）
                    merchantEventPublisher.publish(verified != null && verified == 1 ? "create" : "update", merchantId);
                });
        log.info("管理员审核商家 [merchantId={}] is_verified={}", merchantId, verified);
    }

    /**
     * 管理员查询待审核商家列表（is_verified = 0，分页，附带实名信息）
     *
     * @param page 页码
     * @param size 每页条数
     * @return 分页的商家列表，每个 Merchant 的 realName 字段已填充实名认证信息
     */
    @Override
    public IPage<Merchant> getPendingMerchants(Integer page, Integer size) {
        Page<Merchant> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<Merchant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Merchant::getIsVerified, 0)
                .orderByAsc(Merchant::getId);
        IPage<Merchant> result = merchantMapper.selectPage(pageReq, wrapper);
        // 逐条查询并填充实名认证信息
        if (result.getRecords() != null) {
            for (Merchant m : result.getRecords()) {
                LambdaQueryWrapper<MerchantRealName> rnWrapper = new LambdaQueryWrapper<>();
                rnWrapper.eq(MerchantRealName::getMerchantId, m.getId());
                MerchantRealName realName = realNameMapper.selectOne(rnWrapper);
                m.setRealName(realName);
            }
        }
        return result;
    }

    /**
     * 查询店铺信息修改申请列表
     *
     * @param status 申请状态（pending / approved / rejected）
     * @return 修改申请列表
     */
    @Override
    public List<MerchantModification> getModifications(String status) {
        LambdaQueryWrapper<MerchantModification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MerchantModification::getStatus, status)
                .orderByAsc(MerchantModification::getCreatedAt);
        return modMapper.selectList(wrapper);
    }

    /**
     * 管理员同意信息修改（支持 store 和 realname 两种类型）
     * <p>
     * 解析 changeDetail JSON，根据 type 分别更新 merchants 表或 merchant_real_name 表。
     */
    @Override
    @Transactional
    public void approveModification(Long modId) {
        MerchantModification mod = modMapper.selectById(modId);
        if (mod == null) {
            throw new RuntimeException("修改申请不存在");
        }
        if (!"pending".equals(mod.getStatus())) {
            throw new RuntimeException("该申请已处理，无法重复审核");
        }

        Map<String, String> changes;
        try {
            changes = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    mod.getChangeDetail(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("解析修改内容失败", e);
        }

        if ("realname".equals(mod.getType())) {
            // 实名信息修改 → 更新 merchant_real_name 表
            Long merchantId = mod.getMerchantId();
            LambdaQueryWrapper<MerchantRealName> rnWrapper = new LambdaQueryWrapper<>();
            rnWrapper.eq(MerchantRealName::getMerchantId, merchantId);
            MerchantRealName realName = realNameMapper.selectOne(rnWrapper);
            if (realName == null) {
                throw new RuntimeException("实名信息不存在");
            }
            if (changes.containsKey("realName")) {
                realName.setRealName(changes.get("realName"));
            }
            if (changes.containsKey("cardId")) {
                realName.setCardId(changes.get("cardId"));
            }
            if (changes.containsKey("cardUrl")) {
                realName.setCardUrl(changes.get("cardUrl"));
            }
            realNameMapper.updateById(realName);
            log.info("管理员通过了实名信息修改申请 [modId={}, merchantId={}]", modId, mod.getMerchantId());
        } else {
            // 店铺信息修改 → 更新 merchants 表
            Merchant merchant = merchantMapper.selectById(mod.getMerchantId());
            if (merchant == null) {
                throw new RuntimeException("关联的商家不存在");
            }

            boolean hasUpdate = false;
            LambdaUpdateWrapper<Merchant> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Merchant::getId, mod.getMerchantId());

            if (changes.containsKey("shopName")) {
                updateWrapper.set(Merchant::getShopName, changes.get("shopName"));
                hasUpdate = true;
            }
            if (changes.containsKey("contactName")) {
                updateWrapper.set(Merchant::getContactName, changes.get("contactName"));
                hasUpdate = true;
            }
            if (changes.containsKey("contactPhone")) {
                updateWrapper.set(Merchant::getContactPhone, changes.get("contactPhone"));
                hasUpdate = true;
            }
            if (changes.containsKey("logoUrl")) {
                updateWrapper.set(Merchant::getLogoUrl, changes.get("logoUrl"));
                hasUpdate = true;
            }
            if (changes.containsKey("businessLicense")) {
                updateWrapper.set(Merchant::getBusinessLicense, changes.get("businessLicense"));
                hasUpdate = true;
            }
            if (changes.containsKey("shopDescription")) {
                updateWrapper.set(Merchant::getShopDescription, changes.get("shopDescription"));
                hasUpdate = true;
            }
            if (changes.containsKey("banners")) {
                updateWrapper.set(Merchant::getBanners, changes.get("banners"));
                hasUpdate = true;
            }

            if (hasUpdate) {
                merchantMapper.update(null, updateWrapper);
                // 事务提交后发 MQ，搜索服务同步 ES
                // 事务安全：用 AfterCommitExecutor（无事务时立即发）
                Long changedMerchantId = mod.getMerchantId();
                AfterCommitExecutor.run(
                        () -> merchantEventPublisher.publish("update", changedMerchantId));
            }
            log.info("管理员通过了店铺信息修改申请 [modId={}, merchantId={}]", modId, mod.getMerchantId());
        }

        // 更新修改申请状态
        mod.setStatus("approved");
        modMapper.updateById(mod);

        // 事务提交后清缓存 + 同步商家索引
        Long approvedMerchantId = mod.getMerchantId();
        AfterCommitExecutor.run(
                () -> {
                    // 清除相关缓存（列表查询改造：不再清商家分页列表缓存，列表已改查 ES 索引）
                    Merchant merchant = merchantMapper.selectById(approvedMerchantId);
                    redisService.deleteKey(RedisService.CACHE_MERCHANT_PUBLIC + approvedMerchantId);
                    if (merchant != null) {
                        redisService.deleteKey(RedisService.CACHE_MERCHANT + merchant.getUserId());
                        redisService.deleteKey(RedisService.CACHE_MERCHANT_DETAIL + merchant.getUserId());
                    }
                },
                () -> {
                    // 修改审核通过后同步商家索引（店铺名/Logo 可能已变）
                    indexEventPublisher.publishUpsert(
                            IndexChangedEvent.EntityType.MERCHANT, approvedMerchantId);
                });

    }

    /**
     * 管理员拒绝店铺信息修改
     * <p>
     * 仅将修改申请状态设为 rejected，不修改 merchants 表。
     *
     * @param modId 修改申请ID
     */
    @Override
    @Transactional
    public void rejectModification(Long modId) {
        MerchantModification mod = modMapper.selectById(modId);
        if (mod == null) {
            throw new RuntimeException("修改申请不存在");
        }
        if (!"pending".equals(mod.getStatus())) {
            throw new RuntimeException("该申请已处理，无法重复审核");
        }
        mod.setStatus("rejected");
        modMapper.updateById(mod);
        log.info("管理员拒绝了店铺信息修改申请 [modId={}, merchantId={}]", modId, mod.getMerchantId());
    }


    // ---------- 工具方法 ----------

    /**
     * 事务提交后清理商户相关缓存（延迟双删）
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor}。
     * 原实现直接调用 {@code registerSynchronization}，在**没有事务**时会抛
     * {@code IllegalStateException}；AfterCommitExecutor 在无事务时退化为立即执行，
     * 避免因调用方式变化而丢失缓存清理。
     *
     * @param userId 商户对应的用户ID（clearShopCache 以 userId 为 key）
     */
    private void evictMerchantCacheAfterCommit(Long userId) {
        AfterCommitExecutor.run(() -> {
            // 1. 立即删除（让缓存尽快失效）
            // 列表查询改造：不再清商家分页列表缓存（列表已改查 ES 索引）
            redisService.clearShopCache(userId);
            // 2. 延迟再删一次（应对主从延迟 / 并发读旧数据）
            cacheDelayScheduler.schedule(() -> {
                try {
                    redisService.clearShopCache(userId);
                    log.info("延迟双删成功, userId={}", userId);
                } catch (Exception e) {
                    log.error("延迟双删失败, userId={}", userId, e);
                }
            }, 300, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * 根据 userId 查询并返回 merchantId
     * <p>
     * 微服务拆分说明：替代原 getMerchantIdByToken(token)（token 解析 + 查表）。
     * 拆分后 userId 由网关 X-User-Id 头提供。
     */
    @Override
    public Long getMerchantIdByUserId(Long userId) {
        Merchant m = merchantMapper.selectByUserId(userId);
        if (m == null) throw new RuntimeException("未找到对应商家");
        return m.getId();
    }

    /**
     * 商家自行更新账号基础信息（username / phone / email）
     * <p>
     * 仅允许更新当前登录用户自己的 users 表字段，不涉及 merchants 表。
     * <p>
     * 微服务拆分说明：原方法在单体 UserService.updateAccount(token,...)，更新 users 表（归属 shopai-user）。
     * 拆分后该接口路由到 shopai-merchant（路径 /api/merchant/me/account）；按「独立库」处理后
     * users 表只有用户服务能写，故本方法改为 Feign 调用户服务内部接口完成实际更新。
     * <p>
     * <b>为什么本方法没有 {@code @Transactional}</b>（原代码有，属于误导，已移除）：
     * 本方法**对本地库没有任何写操作**，只做三件事 —— 两次 Feign 调用 + 清本地 Redis key。
     * 加 {@code @Transactional} 会给人「这些操作是原子的、能一起回滚」的错觉，而事实是：
     * <ul>
     *   <li>Feign 调用在**远端**执行，用户服务的更新在它自己的事务里**立即提交**，
     *       本服务的本地事务回滚**无法**撤销它。真正的跨服务原子性需要 Seata 全局事务，
     *       本项目当前未启用（见设计文档第 8 节）；</li>
     *   <li>一个空事务只是白白占着数据库连接。</li>
     * </ul>
     * 顺带说明「时机」问题：这里删缓存在 Feign 返回之后执行，而 Feign 返回时用户服务的
     * 写**已经提交**，所以不存在「事务未提交就删缓存」的不一致风险 ——
     * 这一点与上一轮修的那批「事务内删缓存/发 MQ」是不同的情况。
     */
    @Override
    public void updateAccount(Long userId, String username, String phone, String email) {
        // 先确认用户存在（原实现用 selectByUserId 判空并抛出「用户不存在」）
        Result<UserInfo> query = null;
        try {
            query = userInternalClient.getUser(userId);
        } catch (Exception e) {
            log.error("调用用户服务查询用户失败, userId={}", userId, e);
            throw new RuntimeException("账号信息更新失败：用户服务不可用");
        }
        if (query == null || query.getCode() == null || query.getCode() != 200 || query.getData() == null) {
            throw new RuntimeException("用户不存在");
        }

        // 交由用户服务执行实际更新（users 表归属用户服务）。
        // 说明：该内部接口内部会清用户缓存并同步 user 索引（见 UserServiceImpl.updateAccount）。
        int rows;
        try {
            Result<Integer> r = userInternalClient.updateAccount(userId, username, phone, email);
            if (r == null || r.getCode() == null || r.getCode() != 200 || r.getData() == null) {
                throw new RuntimeException("账号信息更新失败");
            }
            rows = r.getData();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用用户服务更新账号失败, userId={}", userId, e);
            throw new RuntimeException("账号信息更新失败：用户服务不可用");
        }
        if (rows <= 0) {
            throw new RuntimeException("账号信息更新失败");
        }

        // 清除本地商户缓存：getMerchantById / selectDetailByUserId 的缓存结果里
        // 携带了 users 信息（Merchant.user），账号改了必须一并失效，否则店铺资料页
        // 最长 300 秒（TTL）仍显示旧用户名/手机/邮箱。
        //
        // 用 AfterCommitExecutor 而非直接删：当前无事务 → 立即执行（与原来一致）；
        // 将来若有人给本方法加上事务，也会自动延后到提交后，避免出现「未提交就删缓存」。
        AfterCommitExecutor.run(() -> {
            redisService.deleteKey(RedisService.CACHE_MERCHANT + userId);
            redisService.deleteKey(RedisService.CACHE_MERCHANT_DETAIL + userId);
        });
        log.info("商家 [userId={}] 更新了账号信息", userId);
    }

    // ==================== 财务中心 ====================

    /**
     * 获取商家资金概览
     */
    @Override
    public Map<String, Object> getFinanceOverview(Long userId) {
        Merchant m = merchantMapper.selectByUserId(userId);
        if (m == null) throw new RuntimeException("未找到对应商家");
        return Map.of(
            "merchantId", m.getId(),
            "totalRevenue", m.getTotalRevenue() != null ? m.getTotalRevenue() : BigDecimal.ZERO,
            "availableBalance", m.getAvailableBalance() != null ? m.getAvailableBalance() : BigDecimal.ZERO,
            "frozenBalance", m.getFrozenBalance() != null ? m.getFrozenBalance() : BigDecimal.ZERO
        );
    }

    /**
     * 申请提现
     */
    @Override
    @Transactional
    public void applyWithdraw(Long userId, BigDecimal amount, String remark) {
        Merchant m = merchantMapper.selectByUserId(userId);
        if (m == null) throw new RuntimeException("未找到对应商家");

        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException("提现金额必须大于0");

        if (amount.compareTo(m.getAvailableBalance()) > 0)
            throw new RuntimeException("可提现余额不足，当前可用：" + m.getAvailableBalance());

        // 扣减可用余额：必须走原子增量，不能用「selectById + set + updateById」。
        // 读改写的两个问题（与 changeFunds 里修过的是同一类）：
        //   1) 并发丢更新 —— 同一时刻若有资金消息（pay / refund_approve）也在动这一行，
        //      两边都读到旧值、各自写回，其中一次增量被覆盖；
        //   2) 全实体覆盖 —— updateById 会把 total_revenue / frozen_balance 一起写回，
        //      用的还是读到时的旧值，同样会抹掉别的路径刚做的增量。
        // 说明：上面的「余额是否够」是普通读校验，与下面的原子扣减之间仍有极窄的竞态
        // （两个并发提现都通过校验）。这里保留友好报错，最终一致性由管理员审核兜底；
        // 若要彻底消除，需要给扣减加「available >= amount」的条件更新。
        int rows = merchantMapper.changeFundsAtomic(m.getId(), null, amount.negate(), null);
        if (rows <= 0) {
            throw new RuntimeException("提现失败：商户资金状态异常");
        }

        // 创建提现记录
        MerchantWithdraw w = new MerchantWithdraw();
        w.setMerchantId(m.getId());
        w.setAmount(amount);
        w.setStatus("pending");
        w.setRemark(remark);
        w.setCreatedAt(LocalDateTime.now());
        withdrawMapper.insert(w);

        // 清除缓存：可用余额已变化，必须让商户详情/公开信息缓存一并失效。
        // 改为统一走 MerchantFinanceCacheEvictor（同时清 userId 与 merchantId 两个维度的
        // 三个 key，且在事务提交后执行）—— 原实现只删了 CACHE_MERCHANT + userId 一个 key，
        // 会漏掉 cache:merchant:detail:{userId} 与 cache:merchant:public:{merchantId}。
        merchantFinanceCacheEvictor.evict(userId, m.getId());
    }

    /**
     * 查询提现记录
     */
    @Override
    public List<MerchantWithdraw> getWithdrawRecords(Long userId) {
        Merchant m = merchantMapper.selectByUserId(userId);
        if (m == null) throw new RuntimeException("未找到对应商家");

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MerchantWithdraw> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(MerchantWithdraw::getMerchantId, m.getId())
               .orderByDesc(MerchantWithdraw::getCreatedAt);
        return withdrawMapper.selectList(wrapper);
    }

    // ==================== 管理员财务 ====================

    /**
     * 管理员查询所有待处理提现
     */
    @Override
    public List<MerchantWithdraw> getPendingWithdraws() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MerchantWithdraw> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(MerchantWithdraw::getStatus, "pending")
               .orderByAsc(MerchantWithdraw::getCreatedAt);
        return withdrawMapper.selectList(wrapper);
    }

    /**
     * 管理员处理提现（通过/拒绝）
     */
    @Override
    @Transactional
    public void handleWithdraw(Long withdrawId, String action) {
        MerchantWithdraw w = withdrawMapper.selectById(withdrawId);
        if (w == null) throw new RuntimeException("提现记录不存在");
        if (!"pending".equals(w.getStatus())) throw new RuntimeException("该提现已处理");

        if ("approved".equals(action)) {
            w.setStatus("completed");
        } else if ("rejected".equals(action)) {
            w.setStatus("rejected");
            // 退还余额：同样必须走原子增量。
            // 原实现是 m.setAvailableBalance(m.getAvailableBalance().add(w.getAmount()))
            // + merchantMapper.updateById(m) ——
            //   1) 并发丢更新：与并行的资金消息互相覆盖，商家凭空少钱；
            //   2) 全实体覆盖：updateById 会把 total_revenue / frozen_balance
            //      按「读出来那一刻的旧值」写回，抹掉别的路径刚做的增量。
            // 这里 selectById 只为拿 userId 用于清缓存（MerchantFinanceCacheEvictor
            // 需要 userId 与 merchantId 两个维度才能清掉全部三个 key）。
            Merchant m = merchantMapper.selectById(w.getMerchantId());
            if (m != null) {
                int rows = merchantMapper.changeFundsAtomic(m.getId(), null, w.getAmount(), null);
                if (rows <= 0) {
                    log.warn("提现驳回退还余额影响 0 行, withdrawId={}, merchantId={}",
                            withdrawId, m.getId());
                }
                // 修复：原实现退还余额后**没有清理任何缓存**，
                // 导致 cache:merchant:{userId} / detail / public 里的旧余额最长存活到 TTL 到期。
                // 已统一走 MerchantFinanceCacheEvictor（含 userId，可清全部三个 key）。
                merchantFinanceCacheEvictor.evict(m.getUserId(), m.getId());
            }
        } else {
            throw new RuntimeException("操作类型错误: approved 或 rejected");
        }
        w.setUpdatedAt(LocalDateTime.now());
        withdrawMapper.updateById(w);
    }

    // ==================== 周营收归档 ====================

    /**
     * 每周一 00:00 执行：归档上周营收并重置 total_revenue 为 0
     */
    @Override
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 0 * * MON")
    @Transactional
    public void archiveWeeklyRevenue() {
        log.info("=== 开始周营收归档 ===");
        List<Merchant> allMerchants = merchantMapper.selectList(Wrappers.emptyWrapper());
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));

        int archived = 0;
        for (Merchant m : allMerchants) {
            // 统计本周完成订单数
            int orderCount = countWeeklyCompletedOrders(m.getId(), weekStart, weekEnd);
            // 归零前的营收
            BigDecimal revenue = m.getTotalRevenue() != null ? m.getTotalRevenue() : BigDecimal.ZERO;

            if (revenue.compareTo(BigDecimal.ZERO) == 0 && orderCount == 0) continue;

            MerchantWeeklyRevenue record = new MerchantWeeklyRevenue();
            record.setMerchantId(m.getId());
            record.setWeekStart(weekStart);
            record.setWeekEnd(weekEnd);
            record.setTotalRevenue(revenue);
            record.setTotalOrders(orderCount);
            record.setCreatedAt(LocalDateTime.now());
            weeklyRevenueMapper.insert(record);

            // 归零：只 SET total_revenue 一列，不能 updateById(整个实体)。
            // 本方法是「先 selectList 全表，再在循环里逐条写回」，循环窗口可能很长；
            // 若期间有支付/退款落到某个商家，updateById 会把 available_balance /
            // frozen_balance 按「循环开始时的旧值」写回，把那笔增量抹掉。
            merchantMapper.update(null, Wrappers.<Merchant>lambdaUpdate()
                    .eq(Merchant::getId, m.getId())
                    .set(Merchant::getTotalRevenue, BigDecimal.ZERO));
            // 修复：原实现归零营收后**没有清理任何缓存**，导致
            // cache:merchant:{userId} / detail 里的 totalRevenue 会一直显示归零前的旧值，
            // 直到 TTL 到期（缓存的是整个 Merchant，含资金字段）。
            // 商户的 userId 从已查出的实体直接取得，因此三个 key 都能清掉。
            merchantFinanceCacheEvictor.evict(m.getUserId(), m.getId());
            archived++;
        }
        log.info("=== 周营收归档完成，归档 {} 个商家 ===", archived);
    }

    /**
     * 统计某商家本周完成订单数
     * <p>
     * 微服务拆分说明：orders 表归属 shopai-order 服务（独立库），原 OrderRefMapper
     * 跨库直查已改为 Feign 调订单服务内部接口
     * （GET /api/order/internal/count）。
     * <p>
     * 降级策略：订单服务不可用时返回 0（归档记录仍会生成，但订单数为 0），
     * 避免周营收归档定时任务整体失败。
     */
    private int countWeeklyCompletedOrders(Long merchantId, LocalDate weekStart, LocalDate weekEnd) {
        try {
            Result<Integer> r = orderInternalClient.countCompletedOrders(
                    merchantId,
                    "completed,received",
                    weekStart.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    weekEnd.atTime(23, 59, 59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData();
            }
            log.warn("统计周完成订单数返回异常, merchantId={}, resp={}", merchantId, r == null ? null : r.getMessage());
        } catch (Exception e) {
            log.error("调用订单服务统计周完成订单数失败, merchantId={}", merchantId, e);
        }
        return 0;
    }

    /** 查询商家历史周营收记录 */
    @Override
    public List<MerchantWeeklyRevenue> getWeeklyRevenueHistory(Long userId) {
        Merchant m = merchantMapper.selectByUserId(userId);
        if (m == null) throw new RuntimeException("未找到对应商家");
        return weeklyRevenueMapper.selectByMerchantId(m.getId());
    }

    /** 更新商家评分（由 ReviewService 调用）
     *  <p>微服务拆分说明：评分变化同步发 MQ merchant.changed(update)，搜索服务更新 ES 的 merchant 索引。
     *  <p><b>事务时机说明（已评估，刻意保持即时发送）</b>：
     *  本方法自身不带 {@code @Transactional}（只有一条 update + 清缓存 + 发消息），
     *  当前唯一的调用方是 {@code ReviewServiceImpl.updateMerchantRating}，
     *  而它是在 {@code afterCommit} 回调里调用的 —— 也就是说**调用时事务已提交**，
     *  所以这里的 {@code publish} 虽然写法上是「即时」，实际时机已经正确。
     *  <p>为什么不改成 {@code AfterCommitExecutor}：那会额外包一层「判断有无事务」的逻辑，
     *  却改变不了当前行为（无事务时它也是立即执行）。保持现状 + 本注释，是为了避免
     *  读者误以为这里存在「事务未提交就发 MQ」的问题。
     *  <p><b>若将来被事务内直接调用</b>（例如直接在某个 {@code @Transactional} 方法里
     *  调 updateRating），必须改为 after-commit：否则消费者回源会读到旧评分，
     *  且事务回滚后索引已被改写。 */
    @Override
    public void updateRating(Long merchantId, BigDecimal rating) {
        // 【必须做定向更新，绝不能用 updateById(new Merchant())】
        //
        // Merchant 实体的 totalRevenue / availableBalance / frozenBalance 带有
        // `= BigDecimal.ZERO` 的字段初始化值（不是 null）。MyBatis-Plus 默认的
        // NOT_NULL 更新策略只会跳过 **null**，不会跳过 0 —— 于是
        //   Merchant m = new Merchant(); m.setId(id); m.setRating(rating); updateById(m);
        // 生成的是「rating = ?, total_revenue = 0, available_balance = 0, frozen_balance = 0」，
        // 一次评价就把商家全部资金抹成 0。
        //
        // 真实事故（2026-10-18 00:47:36）：顾客提交一条商品评价 → addReview 的
        // afterCommit 回调 → updateMerchantRating → 本方法 → 商家 2078784861670813697
        // 的 total_revenue / available_balance / frozen_balance 全部被写成 0。
        //
        // 因此这里只用 LambdaUpdateWrapper 精确 SET rating 一列。
        int rows = merchantMapper.update(null, Wrappers.<Merchant>lambdaUpdate()
                .eq(Merchant::getId, merchantId)
                .set(Merchant::getRating, rating));
        if (rows == 0) {
            // 商家不存在时不抛异常：评分更新失败不应让评价事务回滚
            log.warn("更新商家评分影响 0 行，商家可能不存在: merchantId={}", merchantId);
        }
        // 清除相关缓存，保证公开信息/详情缓存与最新评分一致
        // 列表查询改造：不再清商家分页列表缓存（列表已改查 ES 索引，评分即其排序字段，
        // 通过下面的 MQ 事件同步到索引）
        redisService.deleteKey(RedisService.CACHE_MERCHANT_PUBLIC + merchantId);

        merchantEventPublisher.publish("update", merchantId);
    }

    /** 根据 userId 查 Merchant（内部使用，不校验角色） */
    @Override
    public Merchant getByUserId(Long userId) {
        return merchantMapper.selectByUserId(userId);
    }

    // ==================== 资金变更（供订单服务内部接口调用） ====================

    /**
     * 原子变更商家资金（增量语义）
     * <p>
     * 微服务拆分说明：替代订单服务原先「{@code MerchantRefMapper.selectById + updateById}」
     * 的跨库读改写 —— 由本服务在同一本地事务内按增量累加，既消除跨库写，
     * 也避免并发下的资金覆盖。
     * <p>
     * <b>为什么必须走 Service 而不是直接在 Controller 里 updateById</b>：
     * 资金字段属于商家详情缓存的一部分，只更新数据库会导致缓存不一致。
     *
     * @param request 资金变动请求（merchantId 必填）
     * @return true 表示成功；false 表示商家不存在
     */
    @Override
    @Transactional
    public boolean changeFunds(MerchantFundsChangeRequest request) {
        if (request == null || request.getMerchantId() == null) {
            return false;
        }

        // 微服务拆分说明（读改写 → 原子增量）：
        // 原实现是 selectById 拿实体 → Java 里累加 → updateById 写回。两个问题：
        //   1) 并发丢更新：两个请求都读到 55，各自 +45，最后都写 100（应为 145），
        //      商户凭空少钱；
        //   2) 持锁窗口长：SELECT 与 UPDATE 两条 SQL 之间还夹着实体装配，
        //      锁持有时间明显长于单条 UPDATE，其它事务（如支付）更容易等锁超时。
        // 改为单条原子 UPDATE（SET x = x + delta）后两个问题一起消失。
        // 注意 user_id 仍需要查一次：清缓存要按 userId 维度清 key（见下方 evict）。
        Merchant m = merchantMapper.selectById(request.getMerchantId());
        if (m == null) {
            return false;
        }

        int rows = merchantMapper.changeFundsAtomic(
                request.getMerchantId(),
                request.getRevenueDelta(),
                request.getAvailableDelta(),
                request.getFrozenDelta());

        // rows == 0：商家已存在，所以只可能是 WHERE 里的「frozen 不得为负」护栏命中。
        // 绝不能当作成功返回 true —— 调用方据此 markDone，护栏就被静默吞掉，
        // 脏数据继续存在且无人知晓。返回 false 让消息保持 pending 并进入重试/告警。
        if (rows == 0) {
            log.error("商家资金变更被拒绝（冻结余额将变为负数，疑似资金幂等键错误）: "
                            + "merchantId={}, reason={}, revenueDelta={}, availableDelta={}, frozenDelta={}",
                    request.getMerchantId(), request.getReason(), request.getRevenueDelta(),
                    request.getAvailableDelta(), request.getFrozenDelta());
            return false;
        }

        // 清缓存：getMerchantById(userId) 与 getMerchantDetail(userId) 会把整个 Merchant
        // （含 totalRevenue / availableBalance / frozenBalance）缓存，只改库不清缓存的话，
        // 商家在「财务中心」/ 店铺资料页看到的仍是旧余额。
        // 统一走 MerchantFinanceCacheEvictor：三个 key 一处维护、且在事务提交后执行。
        merchantFinanceCacheEvictor.evict(m.getUserId(), m.getId());

        // 说明：不额外发 merchant.changed。资金字段不在 merchant 索引文档里
        // （见 MerchantDocument），发事件只会带来无谓的索引写入。
        // 若将来把余额加入商家索引，需要在下面补发事件：
        // indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.MERCHANT, m.getId());
        //
        // 日志只记录本次【增量】而不是变更后的余额：改原子 UPDATE 后不再回查库拿新值
        // （多一次查询就白白拉长持锁窗口）。要看当前余额，查 cache:merchant:{userId}
        // 或直接查库即可。
        log.info("商家资金变更: merchantId={}, reason={}, revenueDelta={}, availableDelta={}, frozenDelta={}",
                m.getId(), request.getReason(), request.getRevenueDelta(),
                request.getAvailableDelta(), request.getFrozenDelta());
        return true;
    }
}
