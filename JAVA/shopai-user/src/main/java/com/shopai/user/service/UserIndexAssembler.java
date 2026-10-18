package com.shopai.user.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.index.UserIndexItem;
import com.shopai.user.entity.Customer;
import com.shopai.user.entity.User;
import com.shopai.user.feign.MerchantInternalClient;
import com.shopai.user.mapper.CustomerMapper;
import com.shopai.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户索引视图组装服务
 * <p>
 * 微服务拆分说明（列表查询改造）：后台用户列表改由 ES 承载后，索引文档需要两个
 * 「原本靠跨库 JOIN 才能拿到」的检索字段：
 * <ul>
 *   <li>{@code customerNickname}：原 SQL {@code users LEFT JOIN customers}
 *       （按客户昵称搜用户）→ 现由本服务查本地 customers 表补齐；</li>
 *   <li>{@code shopName}：原 SQL {@code users LEFT JOIN merchants}
 *       （按店铺名搜用户）→ 现由本服务 Feign 调商家服务内部接口补齐。</li>
 * </ul>
 * 补齐后由搜索服务写入索引，列表查询即可在没有 JOIN、没有缓存的前提下
 * 继续支持「昵称/店铺名」关键字搜索。
 * <p>
 * 安全说明（重要）：本视图**绝不包含** {@code passwordHash}。用户索引只服务
 * 后台列表的筛选/检索/分页，因此仅装配非敏感字段。
 * <p>
 * 降级策略：商家服务不可用时 {@code shopName} 留空（该用户仍可按用户名/手机/邮箱/
 * 昵称检索到），不阻断索引写入。
 */
@Service
public class UserIndexAssembler {

    private static final Logger log = LoggerFactory.getLogger(UserIndexAssembler.class);

    /** 全量重建时每批处理的用户数（避免一次拉取过多） */
    private static final int BATCH_SIZE = 500;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private MerchantInternalClient merchantInternalClient;

    /**
     * 组装单个用户的索引视图
     *
     * @param userId 用户ID
     * @return 索引视图；用户不存在时返回 null
     */
    public UserIndexItem assemble(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        List<UserIndexItem> items = assembleBatch(Collections.singletonList(user));
        return items.isEmpty() ? null : items.get(0);
    }

    /**
     * 分页组装用户索引视图（全量重建用）
     * <p>
     * 分批的原因：用户量通常远大于商家/商品，一次性全量组装会占用大量内存与 Feign 报文。
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     */
    public List<UserIndexItem> assemblePage(int page, int size) {
        int safeSize = size <= 0 ? BATCH_SIZE : size;
        int offset = Math.max(0, (Math.max(1, page) - 1) * safeSize);
        List<User> users = userMapper.selectPageForIndex(offset, safeSize);
        return assembleBatch(users);
    }

    /**
     * 批量组装：先批量取昵称与店铺名（各一次调用），再逐条映射
     */
    private List<UserIndexItem> assembleBatch(List<User> users) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 客户昵称（本地 customers 表；原为 JOIN customers）
        Map<Long, String> nicknameByUserId = loadCustomerNicknames(users);

        // 2. 店铺名（Feign 调商家服务；原为 JOIN merchants）
        Map<Long, String> shopNameByUserId = loadShopNames(users);

        List<UserIndexItem> items = new ArrayList<>(users.size());
        for (User u : users) {
            UserIndexItem item = new UserIndexItem();
            item.setId(u.getId() == null ? null : String.valueOf(u.getId()));
            item.setUsername(u.getUsername());
            item.setPhone(u.getPhone());
            item.setEmail(u.getEmail());
            item.setRole(u.getRole());
            item.setStatus(u.getStatus());
            item.setCreateTime(u.getCreateTime());
            item.setUpdateTime(u.getUpdateTime());
            // 注意：此处不装配 passwordHash，索引中不出现任何凭据字段
            if (u.getId() != null) {
                item.setCustomerNickname(nicknameByUserId.get(u.getId()));
                item.setShopName(shopNameByUserId.get(u.getId()));
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 批量取「userId → 客户昵称」
     * <p>
     * 说明：customers 表属于本服务，因此直接本地查询，无需 Feign。
     * 一次查出所有 customer 记录再按 userId 建映射，避免 N+1。
     */
    private Map<Long, String> loadCustomerNicknames(List<User> users) {
        Map<Long, String> map = new HashMap<>();
        List<Long> userIds = users.stream()
                .map(User::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return map;
        }
        try {
            List<Customer> customers = customerMapper.selectByUserIds(userIds);
            for (Customer c : customers) {
                if (c.getUserId() != null && c.getNickname() != null) {
                    map.put(c.getUserId(), c.getNickname());
                }
            }
        } catch (Exception e) {
            log.error("批量获取客户昵称失败（用户索引将缺少昵称检索字段），userIds={}", userIds, e);
        }
        return map;
    }

    /**
     * 批量取「userId → 店铺名」
     * <p>
     * merchants 表归属 shopai-merchant 服务，逐个调 Feign 会形成 N+1，
     * 故这里逐个查询后再合并（商家服务内部接口按 userId 单查；
     * 只有 merchant 角色用户才会命中，实际调用次数远小于用户总数）。
     */
    private Map<Long, String> loadShopNames(List<User> users) {
        Map<Long, String> map = new HashMap<>();
        for (User u : users) {
            // 只有商家角色需要店铺名，customer/admin 直接跳过，避免无谓的跨服务调用
            if (u.getId() == null || !"merchant".equals(u.getRole())) {
                continue;
            }
            try {
                Result<MerchantInfo> r = merchantInternalClient.getByUserId(u.getId());
                if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                    map.put(u.getId(), r.getData().getShopName());
                }
            } catch (Exception e) {
                log.error("获取商家店铺名失败（该用户索引将缺少店铺名），userId={}", u.getId(), e);
            }
        }
        return map;
    }
}
