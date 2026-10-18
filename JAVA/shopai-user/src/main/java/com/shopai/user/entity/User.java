package com.shopai.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应 users 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.User 迁移至 shopai-user 服务，
 * 包名变更为 com.shopai.user.entity。
 */
@Data
@TableName("users")
public class User {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String username;
    private String passwordHash;
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
