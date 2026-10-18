package com.shopai.order.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SKU 引用视图对象（订单服务内的展示用精简版）
 * <p>
 * 微服务拆分说明：product_skus 表归属 shopai-product 服务。购物车列表原先通过
 * SQL {@code JOIN product_skus} 填充规格/价格/图片；按「独立库」处理后不再跨库联查，
 * 改由 {@link com.shopai.order.service.ShoppingCartServiceImpl} 通过 Feign 调商品服务
 * 内部接口，把结果装配到本对象，再挂到 {@code ShoppingCart.sku}。
 * <p>
 * 注意：本类**不是数据库实体**，不带任何 MyBatis 注解，也不会被 Mapper 使用。
 * 库存（stock）已由 shopai-inventory 服务的独立 inventory 表接管，
 * 本对象不再返回库存，前端如需展示实时库存应走库存服务接口。
 */
@Data
public class ProductSkuRef {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    private String skuCode;

    /** 颜色，如 "藏青色" */
    private String color;

    /** 尺码，如 "M" / "XL" / "38" */
    private String size;

    private BigDecimal price;

    private String imageUrl;

    private BigDecimal weight;

    /** 所属商品（预留：如需由 SKU 反查商品可由此扩展） */
    private Object product;
}
