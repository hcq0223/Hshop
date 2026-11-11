package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商户实体
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.Merchant 迁移至 shopai-merchant 服务，
 * 包名变更为 com.shopai.merchant.entity。
 * 关联的 User 基础信息改用 UserRef（users 表归属 shopai-user，共享单库阶段直查，后续拆库改 Feign）。
 */
@Data
@TableName("merchants")
public class Merchant {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String shopName;
    private String logoUrl;
    private String businessLicense;
    private String contactName;
    private String contactPhone;
    private String shopDescription;

    /** 店铺轮播图（JSON数组字符串，如 ["url1","url2"]） */
    private String banners;

    private BigDecimal rating;

    /** 0:未认证 1:已认证 */
    private Integer isVerified;

    private BigDecimal totalRevenue;
    private BigDecimal availableBalance;
    private BigDecimal frozenBalance;

    /** 挂载关联的 User 基础信息（联查使用，非数据库字段）
     *  <p>微服务拆分说明：原为 com.example.demo.entity.User，改为 UserRef（users 表归属用户服务）。 */
    @TableField(exist = false)
    private UserRef user;

    /** 挂载关联的实名认证信息（联查使用，非数据库字段） */
    @TableField(exist = false)
    private MerchantRealName realName;
}
