package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code products} 表归属 shopai-product 服务。订单服务下单时原先通过
 * {@code ProductRefMapper.selectProductById(id)} 直查 products 表，取商品名称/主图/所属商家ID。
 * <p>
 * 拆分为「独立库」后改为 Feign 调用商品服务内部接口
 * （GET /api/product/internal/products?ids=1,2 / GET /api/product/internal/product/{id}），
 * 本 DTO 是该链路的统一返回结构。
 */
@Data
public class ProductInfo implements Serializable {

    /** 商品ID（products.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属商家ID（products.merchant_id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 商品名称 */
    private String name;

    /** 商品主图地址 */
    private String mainImage;

    /** 最低价 */
    private BigDecimal priceMin;

    /** 最高价 */
    private BigDecimal priceMax;

    /** 状态：draft / on_sale / off_sale */
    private String status;
}
