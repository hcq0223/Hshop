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

    /*
     * 资金字段刻意**不给** `= BigDecimal.ZERO` 初值。
     *
     * 原因：MyBatis-Plus 的 updateById 用 NOT_NULL 策略决定哪些列参与 UPDATE，
     * 它只跳过 null，不跳过 0。一旦这些字段有 0 初值，任何「new Merchant() 只设几个字段
     * 再 updateById」的局部更新都会把它们一并写成 0 —— 真实事故就是把商家全部资金抹平。
     * 保持 null 则这些列在局部更新时被跳过，真正需要变更资金一律走
     * MerchantMapper.changeFundsAtomic（原子增量）。
     *
     * 插入时无需担心：三列在库里都是 NOT NULL DEFAULT 0.00，
     * MP 跳过 null 字段后由数据库默认值兜底。
     */
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
