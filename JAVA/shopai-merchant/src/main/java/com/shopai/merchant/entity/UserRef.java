package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户引用实体（商家服务内的只读精简版）
 * <p>
 * 微服务拆分说明：users 表归属 shopai-user 服务。商家详情/入驻/激活账号等环节联查
 * users 表（selectDetailByUserId 的 Merchant.user 关联、activateUser 更新 status）。
 * 当前「共享单库」阶段可直查/直写；后续拆独立库需改为 Feign 调用用户服务。
 */
@Data
@TableName("users")
public class UserRef {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String username;
    private String phone;
    private String email;

    /** customer / merchant / admin */
    private String role;

    /** 1:正常 0:禁用 */
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createTime;

    @TableField("updated_at")
    private LocalDateTime updateTime;
}
