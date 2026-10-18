package com.shopai.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品实体（对应 products 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.Product 迁移至 shopai-product 服务，
 * 包名变更为 com.shopai.product.entity。
 */
@Data
@TableName("products")
public class Product {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private Integer categoryId;
    private String name;
    private String description;
    private String mainImage;

    /** 图片列表，MyBatis-Plus 需配置 JacksonTypeHandler */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 冗余最低价，加速列表页查询 */
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private Integer salesCount;

    /** draft / on_sale / off_sale */
    private String status;

    @TableField("created_at")
    private LocalDateTime createTime;

    // ---- 关联对象 ----
    @TableField(exist = false)
    private Merchant merchant;

    @TableField(exist = false)
    private Category category;

    @TableField(exist = false)
    private List<ProductSku> skuList;

    /** 列表展示用的店铺冗余字段（联查填充） */
    @TableField(exist = false)
    private String shopName;

    @TableField(exist = false)
    private String shopLogo;

    /** 分类名称冗余字段 */
    @TableField(exist = false)
    private String categoryName;

    @TableField(exist = false)
    private String parentCategoryName;
}
