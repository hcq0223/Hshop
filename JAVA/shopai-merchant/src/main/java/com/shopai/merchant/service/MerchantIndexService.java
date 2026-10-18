package com.shopai.merchant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantIndexItem;
import com.shopai.common.dto.internal.UserInfo;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.feign.UserInternalClient;
import com.shopai.merchant.mapper.MerchantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商家搜索索引数据服务（供搜索服务 Feign 拉取）
 * <p>
 * 微服务拆分说明：搜索服务原先通过自己的 {@code MerchantRefMapper} 跨库直查 merchants，
 * 并 {@code INNER JOIN users u ON m.user_id = u.id} 取创建时间，
 * 按 {@code m.is_verified = 1 AND u.status = 1} 过滤已认证且账号正常的商家。
 * <p>
 * 按「独立库」处理后：
 * - merchants 属于本服务，可在本地查询；
 * - users 属于用户服务，只能 Feign 调用；
 * 因此索引源数据的组装职责上移到本服务，搜索服务只负责「拉取 + 写 ES」。
 * <p>
 * 本类不参与商家业务写入，仅供内部接口（{@code /api/merchant/internal/index/**}）使用。
 */
@Service
public class MerchantIndexService {

    private static final Logger log = LoggerFactory.getLogger(MerchantIndexService.class);

    @Autowired
    private MerchantMapper merchantMapper;

    @Autowired
    private UserInternalClient userInternalClient;

    /**
     * 全量构建商家索引条目（搜索服务全量重建 merchant 索引用）
     * <p>
     * 语义与原 SQL 一致：仅返回「已认证（is_verified=1）且账号正常（users.status=1）」的商家。
     */
    public List<MerchantIndexItem> buildAll() {
        List<Merchant> merchants = merchantMapper.selectList(
                Wrappers.<Merchant>lambdaQuery()
                        .eq(Merchant::getIsVerified, 1)
                        .orderByAsc(Merchant::getId));
        return assemble(merchants);
    }

    /**
     * 按商家ID批量构建索引条目（搜索服务 MQ 增量同步用）
     * <p>
     * 说明：与原 {@code selectOneForIndex} 一致，未认证商家返回空列表，
     * 由搜索服务据此从索引中删除该商家。
     *
     * @param ids 商家ID集合
     */
    public List<MerchantIndexItem> buildByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Merchant> merchants = merchantMapper.selectList(
                Wrappers.<Merchant>lambdaQuery()
                        .in(Merchant::getId, ids)
                        .eq(Merchant::getIsVerified, 1)
                        .orderByAsc(Merchant::getId));
        return assemble(merchants);
    }

    /**
     * 组装索引条目
     * <p>
     * 原 SQL 为 {@code INNER JOIN users u}（取 u.created_at 并按 u.status = 1 过滤），
     * users 归属用户服务，改为一次批量 Feign 调用补齐创建时间并过滤账号状态。
     */
    private List<MerchantIndexItem> assemble(List<Merchant> merchants) {
        if (merchants == null || merchants.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 批量取用户信息（一次 Feign 调用），同时用于「账号正常」过滤
        Map<Long, UserInfo> userMap = loadUsers(merchants);

        // 2. 逐条映射；账号不正常（status != 1）的商家不进索引（等价原 INNER JOIN 的过滤）
        List<MerchantIndexItem> items = new ArrayList<>(merchants.size());
        for (Merchant m : merchants) {
            UserInfo user = m.getUserId() == null ? null : userMap.get(m.getUserId());
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                log.debug("商家 [{}] 对应账号不存在或不可用，跳过索引构建", m.getId());
                continue;
            }
            MerchantIndexItem item = new MerchantIndexItem();
            item.setId(m.getId() == null ? null : String.valueOf(m.getId()));
            item.setUserId(m.getUserId() == null ? null : String.valueOf(m.getUserId()));
            item.setShopName(m.getShopName());
            item.setLogoUrl(m.getLogoUrl());
            item.setShopDescription(m.getShopDescription());
            item.setRating(m.getRating());
            item.setIsVerified(m.getIsVerified());
            // 创建时间：原 JOIN users 取 users.created_at，保持一致
            item.setCreatedAt(user.getCreateTime());
            items.add(item);
        }
        return items;
    }

    /**
     * 批量加载商家关联的用户信息
     * <p>
     * 降级策略：用户服务不可用时返回空 Map，导致索引条目为空
     * （宁可暂不同步也不写入状态未知的商家，与原 JOIN 语义相比更保守但安全）。
     */
    private Map<Long, UserInfo> loadUsers(List<Merchant> merchants) {
        Map<Long, UserInfo> map = new HashMap<>();
        String ids = merchants.stream()
                .map(Merchant::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<UserInfo>> r = userInternalClient.listUsers(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (UserInfo u : r.getData()) {
                    if (u.getId() != null) {
                        map.put(u.getId(), u);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取用户信息失败（商家索引将为空），userIds={}", ids, e);
        }
        return map;
    }
}
