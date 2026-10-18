package com.shopai.merchant.feign;

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

@Component
public class CustomerInternalClientFallback implements FallbackFactory<CustomerInternalClient> {
    private static final Logger log = LoggerFactory.getLogger(CustomerInternalClientFallback.class);
    @Override
    public CustomerInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <CustomerInternalClientFallback-merchant> 进入降级, reason={}", reason, cause);
        }
        return new CustomerInternalClient() {
            @Override
            public Result<List<CustomerIdPair>> listCustomerIds(String userIds) {
                log.warn("商户查询客户服务暂时不可用, 方法:listCustomerIds(String userIds), ids={}, reason={}", userIds, reason);
                return null;
            }

            @Override
            public Result<List<CustomerInfo>> listCustomers(String ids) {
                log.warn("商户批量查询客户信息服务暂时不可用, 方法:listCustomers(String ids), ids={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
