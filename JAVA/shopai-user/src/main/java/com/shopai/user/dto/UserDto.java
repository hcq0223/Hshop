package com.shopai.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息DTO（不含敏感字段 passwordHash）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.dto.UserDto 迁移至 shopai-user 服务，
 * 包名变更为 com.shopai.user.dto。原类上的 @TableField(fill=...) 注解无实际意义，已去除。
 */
@Data
public class UserDto {
    private Long id;

    private String username;
    private String phone;
    private String email;

    /** customer / merchant / admin */
    private String role;

    /** 1:正常 0:禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
