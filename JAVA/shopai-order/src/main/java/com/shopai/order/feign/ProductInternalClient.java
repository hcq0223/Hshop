package com.shopai.order.feign;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 Feign 客户端（订单服务侧）
 * <p>
 * 微服务拆分说明：{@code products} / {@code product_skus} 表归属 shopai-product 服务。
 * 订单服务下单时原先通过 {@code ProductSkuRefMapper} / {@code ProductRefMapper} 直查这两张表
 * （共享单库阶段），取价格/规格/图片/商品名/所属商家ID 用于生成订单明细。
 * <p>
 * 按「独立库」处理后，这两张表只有商品服务能访问，改为本 Feign 客户端调用
 * 商品服务内部接口。所有接口均为内部接口（网关不暴露，仅服务间直连）。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_PRODUCT,
        path = InternalApiPaths.PRODUCT_INTERNAL,
        contextId = "order-product",
        fallbackFactory = ProductInternalClientFallback.class
)
public interface ProductInternalClient {

    /**
     * 按 SKU ID 查询 SKU 信息（替代原 ProductSkuRefMapper.selectSkuById）
     * <p>
     * 返回：价格 / 颜色 / 尺码 / 图片 / 所属商品ID。不含库存（库存归 shopai-inventory）。
     *
     * @param id SKU ID
     * @return SKU 信息；不存在时 data 为 null
     */
    @GetMapping("/sku/{id}")
    Result<ProductSkuInfo> getSku(@PathVariable("id") Long id);

    /**
     * 按商品ID查询商品信息（替代原 ProductRefMapper.selectProductById）
     * <p>
     * 返回：商品名称 / 主图 / 所属商家ID / 价格区间 / 状态。
     *
     * @param id 商品ID
     * @return 商品信息；不存在时 data 为 null
     */
    @GetMapping("/product/{id}")
    Result<ProductInfo> getProduct(@PathVariable("id") Long id);

    /**
     * 按商品ID批量查询商品信息
     * <p>
     * 用途：购物车结算时一次补齐多个商品（替代逐个 selectProductById）。
     *
     * @param ids 逗号分隔的商品ID列表，如 "1,2,3"
     * @return 商品信息列表
     */
    @GetMapping("/products")
    Result<List<ProductInfo>> listProducts(@RequestParam("ids") String ids);
}
