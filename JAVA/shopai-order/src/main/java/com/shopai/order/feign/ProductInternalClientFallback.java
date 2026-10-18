package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 商品服务 Feign 降级实现（订单服务侧）
 * <p>
 * <b>降级语义</b>：本客户端的三个方法都是**下单时读取商品/SKU 信息**。
 * 这些数据是下单的必要输入（价格、商品名、所属商家），拿不到就无法继续，
 * 因此降级返回 null / 空列表，让 {@code OrderServiceImpl.getSkuViaFeign}
 * 按其既有逻辑抛「查询SKU失败」→ 触发事务回滚。
 * <p>
 * 不在这里直接抛异常的原因：保持与其他 Fallback 一致的「返回结构」语义，
 * 由调用方统一判断并抛出业务异常，避免降级实现里出现业务判断逻辑
 * （这样「失败处理」只有一处，便于后续统一调整）。
 */
@Component
public class ProductInternalClientFallback implements FallbackFactory<ProductInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(ProductInternalClientFallback.class);

    @Override
    public ProductInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <ProductInternalClient-order> 进入降级, reason={}", reason, cause);
        }
        return new ProductInternalClient() {

            @Override
            public Result<ProductSkuInfo> getSku(Long id) {
                log.error("商品服务暂不可用, 查询SKU失败（下单将回滚）, skuId={}, reason={}", id, reason);
                return Result.ok((ProductSkuInfo) null);
            }

            @Override
            public Result<ProductInfo> getProduct(Long id) {
                log.error("商品服务暂不可用, 查询商品失败（下单将回滚）, productId={}, reason={}", id, reason);
                return Result.ok((ProductInfo) null);
            }

            @Override
            public Result<List<ProductInfo>> listProducts(String ids) {
                log.warn("商品服务暂不可用, 批量查询商品降级为空列表, ids={}, reason={}", ids, reason);
                return Result.ok(Collections.emptyList());
            }
        };
    }
}
