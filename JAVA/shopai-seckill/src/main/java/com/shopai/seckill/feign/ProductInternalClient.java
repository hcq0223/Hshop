package com.shopai.seckill.feign;

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
 * 商品服务 Feign 客户端（秒杀服务侧）
 * <p>
 * <b>本服务不落库任何 SKU / 商品信息</b>，规格（颜色/尺码）、图片、商品名、
 * 店铺信息全部实时从这里取。这样商品改价、改名、换图之后，
 * 秒杀页展示的内容永远是最新的，不会出现「秒杀页还挂着两周前的旧图」。
 * <p>
 * 与之相对，{@code seckill_stock} 表里冗余了 {@code productId} / {@code merchantId}：
 * 那两个是<b>归属关系、不会变</b>，冗余是为了少绕一次调用；
 * 而价格类信息是<b>会变的</b>，一律实时取（其中 {@code originPrice} 是刻意的快照，
 * 只做划线价展示，见 SeckillStock 的注释）。
 * <p>
 * 安全说明：{@code /api/product/internal/**} 不对网关暴露，仅服务间 Feign 直连。
 */
@FeignClient(
        name = InternalApiPaths.SERVICE_PRODUCT,
        path = InternalApiPaths.PRODUCT_INTERNAL,
        contextId = "seckill-product",
        fallbackFactory = ProductInternalClientFallback.class
)
public interface ProductInternalClient {

    /**
     * 查询单个商品（取商品名、主图、所属商家、状态）。
     *
     * @param id 商品ID
     */
    @GetMapping("/product/{id}")
    Result<ProductInfo> getProduct(@PathVariable("id") Long id);

    /**
     * 批量查询商品（活动详情页一次性补齐所有商品的展示信息，避免 N+1 次调用）。
     *
     * @param ids 逗号分隔的商品ID列表
     */
    @GetMapping("/products")
    Result<List<ProductInfo>> listProducts(@RequestParam("ids") String ids);

    /**
     * 查询单个 SKU（取规格、图片、日常价）。
     * <p>
     * 创建秒杀活动时用它校验「秒杀价必须低于日常价」并取原价快照。
     *
     * @param id SKU ID
     */
    @GetMapping("/sku/{id}")
    Result<ProductSkuInfo> getSku(@PathVariable("id") Long id);

    /**
     * 查询商品下的全部 SKU（商家创建活动时选品用）。
     *
     * @param productId 商品ID
     */
    @GetMapping("/sku/by-product/{productId}")
    Result<List<ProductSkuInfo>> listSkusByProduct(@PathVariable("productId") Long productId);
}
