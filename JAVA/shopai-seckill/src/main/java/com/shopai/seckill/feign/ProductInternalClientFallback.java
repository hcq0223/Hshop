package com.shopai.seckill.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品服务 Feign 降级实现（秒杀服务侧）
 * <p>
 * <b>降级策略：一律返回失败（非 200），绝不返回「空」或「默认值」。</b>
 * 原因分场景说明：
 * <ul>
 *   <li><b>创建活动 / 校验价格</b>（{@link #getSku}）：如果降级返回一个默认价，
 *       就可能放行「秒杀价高于日常价」的活动，或者把原价快照存成 0 —
 *       这些脏数据一旦落库，事后再改很麻烦。所以宁可让创建失败。</li>
 *   <li><b>展示类查询</b>（{@link #getProduct}、{@link #listProducts}）：
 *       返回空会让活动详情页显示「商品已下架」这种错误结论，
 *       用户以为活动取消了。返回失败让前端显示「加载失败，请重试」，
 *       语义正确得多。</li>
 * </ul>
 * <p>
 * 特别注意<b>不能</b>在这里返回 {@code Result.ok(空集合)}：
 * 调用方看到空集合会理解为「涉及的商品都不存在」，进而可能把活动标成异常状态。
 */
@Component
public class ProductInternalClientFallback implements FallbackFactory<ProductInternalClient> {

    private static final Logger log = LoggerFactory.getLogger(ProductInternalClientFallback.class);

    @Override
    public ProductInternalClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <ProductInternalClient-seckill> 进入降级, reason={}", reason, cause);
        }
        return new ProductInternalClient() {

            @Override
            public Result<ProductInfo> getProduct(Long id) {
                log.error("商品服务暂不可用, 秒杀活动无法获取商品信息, productId={}, reason={}", id, reason);
                return Result.error("商品服务暂不可用，无法获取商品信息");
            }

            @Override
            public Result<List<ProductInfo>> listProducts(String ids) {
                log.error("商品服务暂不可用, 秒杀活动商品批量查询失败, ids={}, reason={}", ids, reason);
                return Result.error("商品服务暂不可用，无法获取商品信息");
            }

            @Override
            public Result<ProductSkuInfo> getSku(Long id) {
                // 关键：不能返回默认价，否则会放过「秒杀价 > 日常价」的非法活动
                log.error("商品服务暂不可用, 无法校验SKU价格与规格, skuId={}, reason={}", id, reason);
                return Result.error("商品服务暂不可用，无法校验SKU信息");
            }

            @Override
            public Result<List<ProductSkuInfo>> listSkusByProduct(Long productId) {
                log.error("商品服务暂不可用, 无法获取商品SKU列表, productId={}, reason={}", productId, reason);
                return Result.error("商品服务暂不可用，无法获取SKU列表");
            }
        };
    }
}
