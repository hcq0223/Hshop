package com.shopai.user.dto;

import lombok.Data;

/**
 * 注册请求DTO
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.dto.RegisterRequest 迁移至 shopai-user 服务，
 * 包名变更为 com.shopai.user.dto。
 */
@Data
public class RegisterRequest {
    /**
     * 手机号
     */
    private String phone;
    /**
     * 密码
     */
    private String password;
    /**
     * 邮箱
     */
    private String email;
    /**
     * 用户名（唯一）
     */
    private String username;
    /**
     * 角色（可选）
     */
    private String role;

}
