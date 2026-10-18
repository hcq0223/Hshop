package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家信息修改申请表（店铺信息 + 实名信息共用一个表，通过 type 区分）
 * <p>
 * 商家修改信息后不直接更新原表，而是写入此表等待管理员审核。
 * 审核期间商家功能不受影响（is_verified 不变）。
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.MerchantModification 迁移至 shopai-merchant 服务。
 */
@Data
@TableName("merchant_modifications")
public class MerchantModification {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 商家名称（商家信息页可见，方便关联） */
    private String shopName;

    /** 修改类型：store=店铺信息修改, realname=实名信息修改 */
    private String type;

    /** 修改项（JSON格式） */
    private String changeDetail;

    /** pending / approved / rejected */
    private String status;

    private LocalDateTime createdAt;
}
