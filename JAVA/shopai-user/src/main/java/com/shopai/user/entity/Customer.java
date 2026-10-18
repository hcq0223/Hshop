package com.shopai.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 客户实体（对应 customers 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.Customer 迁移至 shopai-user 服务，
 * 包名变更为 com.shopai.user.entity。
 */
@Data
@TableName("customers")
public class Customer {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 关联 users.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickname;
    private String avatarUrl;

    /** male / female / other */
    private String gender;

    /** 默认收货地址ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long defaultAddressId;

    // ---- 非数据库字段，用于联查展示 ----
    @TableField(exist = false)
    private User user;
}
