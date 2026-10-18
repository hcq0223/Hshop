package com.shopai.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车实体（对应 shopping_cart 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.ShoppingCart 迁移至 shopai-order 服务。
 * sku/product 联查字段改为精简引用实体（ProductSkuRef / ProductRef），
 * 拆分后联查仍指向 product_skus/products 表（共享单库阶段），后续拆独立库改为 Feign 补字段。
 */
@Data
@TableName("shopping_cart")
public class ShoppingCart {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private Integer quantity;

    /** 是否选中 1:是 0:否 */
    private Integer selected;

    @TableField("created_at")
    private LocalDateTime createTime;

    // ---- 联查字段 ----
    @TableField(exist = false)
    private ProductSkuRef sku;

    @TableField(exist = false)
    private ProductRef product;
}
