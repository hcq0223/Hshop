package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家实名认证实体
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.MerchantRealName 迁移至 shopai-merchant 服务，
 * 包名变更为 com.shopai.merchant.entity。
 */
@Data
@TableName("merchant_real_name")
public class MerchantRealName {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商家ID（关联 merchants.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 真实姓名 */
    private String realName;

    /** 身份证号码 */
    private String cardId;

    /** 身份证照片URL */
    private String cardUrl;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
