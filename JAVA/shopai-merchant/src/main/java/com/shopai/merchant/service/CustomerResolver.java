package com.shopai.merchant.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.CustomerIdPair;
import com.shopai.merchant.feign.CustomerInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 客户身份解析服务（商家服务侧）
 * <p>
 * 微服务拆分说明：网关在 X-User-Id 头中下发的是 {@code users.id}，而评价业务使用
 * {@code customers.id}。原实现通过 {@code CustomerRefMapper.selectIdByUserId}
 * 跨库直查 customers 表完成映射。
 * <p>
 * 按「独立库」处理后 customers 表归属 shopai-user 服务，本服务统一通过本类
 * （内部走 {@link CustomerInternalClient} Feign 调用用户服务的客户内部接口）完成映射，
 * ReviewController 的「我的评价」「新增评价」两处共用，避免重复实现。
 * <p>
 * 降级策略：用户服务不可用时返回 null，由 Controller 给出业务提示。
 */
@Service
public class CustomerResolver {

    private static final Logger log = LoggerFactory.getLogger(CustomerResolver.class);

    @Autowired
    private CustomerInternalClient customerInternalClient;

    /**
     * userId（users.id）→ customerId（customers.id）
     *
     * @param userId 用户ID（来自网关 X-User-Id 头）
     * @return 客户ID；未创建客户资料或用户服务不可用时返回 null
     */
    public Long toCustomerId(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<List<CustomerIdPair>> r = customerInternalClient.listCustomerIds(String.valueOf(userId));
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (CustomerIdPair pair : r.getData()) {
                    if (pair.getCustomerId() != null) {
                        return pair.getCustomerId();
                    }
                }
            }
        } catch (Exception e) {
            log.error("调用用户服务解析 customerId 失败, userId={}", userId, e);
        }
        return null;
    }
}
