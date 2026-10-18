package com.shopai.user.controller;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.CustomerIdPair;
import com.shopai.common.dto.internal.CustomerInfo;
import com.shopai.common.dto.internal.user.UserIdItem;
import com.shopai.user.entity.Customer;
import com.shopai.user.mapper.CustomerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户服务内部接口（供其他服务通过 Feign 调用，不对网关暴露）
 * <p>
 * 微服务拆分说明：{@code customers} 表归属 shopai-user 服务。按「独立库」处理跨服务访问后，
 * 原先其他服务对 customers 表的直查全部改为调用本控制器：
 * - shopai-order：`X-User-Id`（users.id）→ customerId 映射
 *   （原 CustomerRefMapper.selectIdByUserId），用于购物车/地址/订单接口；
 * - shopai-merchant：评价发布时同样的 userId → customerId 映射
 *   （原 CustomerRefMapper.selectIdByUserId），以及评价列表/详情联查客户昵称/头像
 *   （原 ReviewMapper.xml 的 LEFT JOIN customers）；
 * - shopai-user 自身：后台用户列表按「客户昵称」模糊搜索 users
 *   （原 UserMapper.xml 的 LEFT JOIN customers）。
 * <p>
 * 安全说明：{@code /api/customer/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@RestController
@RequestMapping(InternalApiPaths.CUSTOMER_INTERNAL)
@RequiredArgsConstructor
public class CustomerInternalController {

    private final CustomerMapper customerMapper;

    /**
     * 批量按 userId 查询客户ID映射（userId → customerId）
     * <p>
     * 原调用方：订单服务 CustomerRefMapper.selectIdByUserId、
     * 商家服务 CustomerRefMapper.selectIdByUserId。
     *
     * @param userIds 逗号分隔的用户ID列表，如 "1,2,3"
     * @return 映射列表；未创建客户资料的用户 customerId 为 null
     */
    @GetMapping("/ids")
    public Result<List<CustomerIdPair>> listIdsByUserIds(@RequestParam("userIds") String userIds) {
        List<Long> idList = parseIds(userIds);
        List<CustomerIdPair> result = new ArrayList<>(idList.size());
        for (Long userId : idList) {
            result.add(new CustomerIdPair(userId, customerMapper.selectIdByUserId(userId)));
        }
        return Result.ok(result);
    }

    /**
     * 批量按客户ID查询客户信息（昵称/头像）
     * <p>
     * 原调用方：商家服务 ReviewMapper.xml 的 {@code LEFT JOIN customers}
     * （为评价填充评价人昵称与头像）。
     *
     * @param ids 逗号分隔的客户ID列表，如 "1,2,3"
     * @return 客户信息列表（不存在的ID自动跳过）
     */
    @GetMapping("/batch")
    public Result<List<CustomerInfo>> listByIds(@RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Customer> customers = customerMapper.selectBatchIds(idList);
        List<CustomerInfo> result = new ArrayList<>(customers.size());
        for (Customer c : customers) {
            CustomerInfo info = new CustomerInfo();
            info.setId(c.getId());
            info.setUserId(c.getUserId());
            info.setNickname(c.getNickname());
            info.setAvatarUrl(c.getAvatarUrl());
            result.add(info);
        }
        return Result.ok(result);
    }

    /**
     * 按关键字（昵称）查询匹配的客户 userId 集合
     * <p>
     * 原调用方：用户服务自身后台用户列表搜索——原 SQL 为
     * {@code users LEFT JOIN customers c ... OR c.nickname LIKE %}。
     * customers 表现在仍归属本服务，因此这一步在服务内部完成，
     * 把「匹配到的 userId 集合」作为条件回传给 UserService 再查 users 表，
     * 从而去掉跨库 JOIN。
     *
     * @param keyword 昵称关键字
     * @param limit   最多返回条数（防止集合过大）
     * @return 匹配的 userId 列表
     */
    @GetMapping("/user-ids-by-nickname")
    public Result<List<UserIdItem>> userIdsByNickname(@RequestParam("keyword") String keyword,
                                                      @RequestParam(value = "limit", defaultValue = "1000") int limit) {
        List<Customer> customers = customerMapper.selectByNickname(keyword, 0, limit);
        List<UserIdItem> result = new ArrayList<>(customers.size());
        for (Customer c : customers) {
            if (c.getUserId() == null) {
                continue;
            }
            UserIdItem item = new UserIdItem();
            item.setId(String.valueOf(c.getUserId()));
            result.add(item);
        }
        return Result.ok(result);
    }

    // ==================== 辅助方法 ====================

    /** 解析逗号分隔的ID串，忽略非法片段 */
    private static List<Long> parseIds(String ids) {
        List<Long> result = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            return result;
        }
        for (String part : ids.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.valueOf(s));
            } catch (NumberFormatException ignored) {
                // 忽略非法ID片段
            }
        }
        return result;
    }
}
