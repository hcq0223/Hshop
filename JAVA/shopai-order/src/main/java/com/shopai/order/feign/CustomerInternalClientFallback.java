package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.CustomerIdPair;
import com.shopai.common.dto.internal.CustomerInfo;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 客户服务 Feign 降级实现（订单服务侧）
 * <p>
 * <b>降级语义</b>：两个方法都是**读操作**（userId → customerId 映射、批量取客户信息）。
 * 用户服务不可用时降级为空结果：
 * <ul>
 *   <li>{@code listIdsByUserIds} → 空列表 ⇒ {@code CustomerResolver.toCustomerId} 返回 null
 *       ⇒ 购物车/地址接口按既有逻辑返回「仅顾客身份可操作」类业务提示，而不是 500；</li>
 *   <li>{@code listCustomers} → 空列表 ⇒ 订单索引视图缺少客户昵称（列表页少个昵称），
 *       不影响订单本身可读。</li>
 * </ul>
 * 这里不需要抛异常：这两个调用**不参与**订单写事务的正确性（不像扣库存/资金变更），
 * 失败时「少展示一点信息」是可接受的降级。
 */
@Component
public class CustomerInternalClientFallback implements FallbackFactory<CustomerInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(CustomerInternalClientFallback.class);

    @Override
    public CustomerInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <CustomerInternalClient-order> 进入降级, reason={}", reason, cause);
        }
        return new CustomerInternalClient() {

            @Override
            public Result<List<CustomerIdPair>> listIdsByUserIds(String userIds) {
                log.warn("用户服务暂不可用, userId→customerId 映射降级为空（购物车/地址将提示身份校验失败）, "
                        + "userIds={}, reason={}", userIds, reason);
                return Result.ok(Collections.emptyList());
            }

            @Override
            public Result<List<CustomerInfo>> listCustomers(String ids) {
                log.warn("用户服务暂不可用, 批量查询客户信息降级为空列表, ids={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
