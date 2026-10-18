package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * SKU 信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code product_skus} 表归属 shopai-product 服务。订单服务下单时原先通过
 * {@code ProductSkuRefMapper.selectSkuById(skuId)} 直查 product_skus 表，取价格/规格/图片/商品ID
 * 用于生成订单明细。
 * <p>
 * 拆分为「独立库」后改为 Feign 调用商品服务内部接口（GET /api/product/internal/sku/{id}），
 * 本 DTO 是该链路的统一返回结构。
 * <p>
 * 注意：{@code stock} 字段已被 shopai-inventory 服务接管（独立 inventory 表），
 * 本 DTO 不再返回库存，库存一律通过 shopai-inventory 查询。
 */
@Data
public class ProductSkuInfo implements Serializable {

    /** SKU ID（product_skus.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属商品ID（product_skus.product_id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** SKU 编码 */
    private String skuCode;

    /** 颜色 */
    private String color;

    /** 尺码 */
    private String size;

    /** 售价 */
    private BigDecimal price;

    /** SKU 图片地址 */
    private String imageUrl;

    /** 重量 */
    private BigDecimal weight;
}
