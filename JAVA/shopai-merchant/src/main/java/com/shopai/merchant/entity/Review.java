package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品评价实体
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.Review 迁移至 shopai-merchant 服务，
 * 包名变更为 com.shopai.merchant.entity。
 * 关联的 Customer 改用 CustomerRef（customers 表归属 shopai-user，共享单库阶段直查，后续拆库改 Feign）。
 */
@Data
@TableName("reviews")
public class Review {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 一个订单只能评价一次 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 所属商家，用于评分计算 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 1-5星 */
    private Integer rating;

    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    @TableField("created_at")
    private LocalDateTime createTime;

    /** 关联的客户信息（非数据库字段）
     *  <p>微服务拆分说明：原为 com.example.demo.entity.Customer，改为 CustomerRef。 */
    @TableField(exist = false)
    private CustomerRef customer;

    /** 关联的商品名称（非数据库字段） */
    @TableField(exist = false)
    private String productName;

    /** 关联的商家名称（非数据库字段） */
    @TableField(exist = false)
    private String merchantName;
}
