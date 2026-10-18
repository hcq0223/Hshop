package com.shopai.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细实体（对应 order_items 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.OrderItem 迁移至 shopai-order 服务。
 * 原 merchant 字段类型为完整 Merchant 实体，拆分后改为精简引用 MerchantRef（联查填充）。
 */
@Data
@TableName("order_items")
public class OrderItem {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 下单时商品名快照 */
    private String productName;

    /** 规格快照，如 "藏青色/XL" */
    private String skuAttrs;

    /** 下单时单价快照 */
    private BigDecimal price;

    private Integer quantity;

    /** 商品图快照 */
    private String imageUrl;

    /** 商品销量（联查 products 填充，非数据库字段） */
    @TableField(exist = false)
    private Integer salesCount;

    /** 所属商家（联查 products→merchants 填充，非数据库字段，精简引用实体） */
    @TableField(exist = false)
    private MerchantRef merchant;
}
