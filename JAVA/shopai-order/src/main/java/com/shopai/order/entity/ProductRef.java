package com.shopai.order.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品引用视图对象（订单服务内的展示用精简版）
 * <p>
 * 微服务拆分说明：products 表归属 shopai-product 服务。购物车列表原先通过
 * SQL {@code JOIN products} 填充商品标题/图片/价格区间；按「独立库」处理后不再跨库联查，
 * 改由 {@link com.shopai.order.service.ShoppingCartServiceImpl} 通过 Feign 调商品服务
 * 内部接口，把结果装配到本对象，再挂到 {@code ShoppingCart.product}。
 * <p>
 * 注意：本类**不是数据库实体**，不带任何 MyBatis 注解，也不会被 Mapper 使用。
 */
@Data
public class ProductRef {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 商品名称 */
    private String name;

    /** 商品主图 */
    private String mainImage;

    /** 最低价 */
    private BigDecimal priceMin;

    /** 最高价 */
    private BigDecimal priceMax;

    /** draft / on_sale / off_sale */
    private String status;

    /** 商品所属店铺（预留：购物车如需展示店铺信息可由此扩展） */
    private Object merchant;
}
