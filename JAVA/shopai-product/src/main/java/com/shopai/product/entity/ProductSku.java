package com.shopai.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SKU 实体（对应 product_skus 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.ProductSku 迁移至 shopai-product 服务，
 * 包名变更为 com.shopai.product.entity。
 * 注意：stock 字段拆分后不再由商品服务读写，库存改由 shopai-inventory 服务的 inventory 表管理；
 * 此处保留 stock 字段仅为兼容历史数据展示，库存真实值通过 Feign 批量补字段填充（见 SKUController）。
 */
@Data
@TableName("product_skus")
public class ProductSku {
    @TableId(type = IdType.ASSIGN_ID)
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

    /** 库存（微服务拆分说明：真实库存已迁至 inventory 表，此字段仅历史兼容，由 Feign 覆盖） */
    private Integer stock;

    private String imageUrl;

    /** 重量(g)，运费计算用 */
    private BigDecimal weight;

    /** 挂载关联的 Product（联查使用，非数据库字段） */
    @TableField(exist = false)
    private Product product;
}
