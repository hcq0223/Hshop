package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商家周营收归档实体
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.MerchantWeeklyRevenue 迁移至 shopai-merchant 服务。
 */
@Data
@TableName("merchant_weekly_revenue")
public class MerchantWeeklyRevenue {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private LocalDate weekStart;
    private LocalDate weekEnd;
    private BigDecimal totalRevenue;
    private Integer totalOrders;
    private LocalDateTime createdAt;
}
