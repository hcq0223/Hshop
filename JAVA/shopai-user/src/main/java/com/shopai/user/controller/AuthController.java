package com.shopai.user.controller;

import com.shopai.common.Result;
import com.shopai.common.redis.RedisService;
import com.shopai.common.security.CurrentUser;
import com.shopai.common.security.JwtUtil;
import com.shopai.user.dto.LoginRequest;
import com.shopai.user.dto.RegisterRequest;
import com.shopai.user.entity.User;
import com.shopai.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Controller.AuthController 迁移至 shopai-user 服务。
 * 路径不变（/api/auth/**）。鉴权改造：
 * - changePwd / delete 原本从 Authorization 头取 token 再 jwtUtil.getUserId(token)，
 *   拆分后由网关统一认证并写入 X-User-Id 头，此处改读 CurrentUser.getUserId(request)。
 * - logout 仍读取 Authorization 头把 token 加入黑名单（网关后续校验黑名单）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    private final RedisService redisService;
    private final JwtUtil jwtUtil;
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * 限制登陆次数
     * @param userId
     * @return
     */
    private boolean isRateLimited(Long userId) {
        try {
            return redisService.recordLoginFail(userId) > 5;
        } catch (Exception e) {
            log.warn("Redis: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 规范化输入，去掉多余空格和符号
     * @param input
     * @return
     */
    private String normalize(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        // 简单判断：如果包含 @ 视为邮箱，转小写；否则按手机号处理（去除空格、-等）
        if (trimmed.contains("@")) {
            return trimmed.toLowerCase();
        } else {
            return trimmed.replaceAll("[\\s\\-()+]", "");
        }
    }

    /**
     * 登陆成功后清除登录失败计数
     * @param userId
     */
    private void clearLoginFail(Long userId) {
        redisService.clearLoginFail(userId);
    }

    /**
     * 用户名登录（含Redis限流）
     */
    @PostMapping("/NameLogin")
    public Result<?>NameLogin(@RequestBody LoginRequest login) {
        String normalized = normalize(login.getUsername());
        if (login.getUsername() == null || login.getPassword() == null) return Result.error("用户名或密码不能为空");
        Long l = userService.selectUserIdByUsernameOrPhoneOrEmail(normalized, null, null);
        if (isRateLimited(l)) return Result.error("登录失败次数过多，请5分钟后再试");
        try {
            login.setUsername(normalized);
            Map<String, Object> r = userService.login(login);
            clearLoginFail(l);
            return Result.ok("登录成功", r);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }


    /**
     * 手机号登录（含Redis限流）
     */
    @PostMapping("/PhoneLogin")
    public Result<?>PhoneLogin(@RequestBody LoginRequest login) {
        String normalized = normalize(login.getPhone());
        if (login.getPhone() == null || login.getPassword() == null) return Result.error("手机号或密码不能为空");
        Long l = userService.selectUserIdByUsernameOrPhoneOrEmail(null, normalized, null);
        if (isRateLimited(l)) return Result.error("登录失败次数过多，请5分钟后再试");
        try {
            login.setPhone(normalized);
            Map<String, Object> r = userService.loginByPhone(login);
            clearLoginFail(l);
            return Result.ok("登录成功", r);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 邮箱登录（含Redis限流）
     */
    @PostMapping("/EmailLogin")
    public Result<?>EmailLogin(@RequestBody LoginRequest login) {
        String normalized = normalize(login.getEmail());
        if (login.getEmail() == null || login.getPassword() == null) return Result.error("邮箱或密码不能为空");
        Long l = userService.selectUserIdByUsernameOrPhoneOrEmail(null, null, normalized);
        if (isRateLimited(l)) return Result.error("登录失败次数过多，请5分钟后再试");
        try {
            login.setEmail(normalized);
            Map<String, Object> r = userService.loginByEmail(login);
            clearLoginFail(l);
            return Result.ok("登录成功", r);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 注册
     */
    @PostMapping("/register")
    public Result<?> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.register(request);
            user.setPasswordHash(null);
            String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getRole());
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("token", token);
            result.put("user", user);
            return Result.ok(result);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 修改密码
     * <p>
     * 微服务拆分说明：userId 由网关 X-User-Id 头提供（CurrentUser），不再从 token 解析。
     */
    @PutMapping("/change-pwd")
    public Result<?> changePwd(HttpServletRequest request, @RequestBody LoginRequest login) {
        Long userId = CurrentUser.getUserId(request);
        try {
            userService.updatePasswords(userId, login);
            return Result.ok("密码更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 找回密码
     */
    @PostMapping("/reset-pwd")
    public Result<?> resetPwd(@RequestBody LoginRequest login) {
        try {
            userService.updatePassword(login);
            return Result.ok("密码更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 退出登陆- Token加入黑名单
     */
    @PostMapping("/logout")
    public Result<?> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        if (token != null) {
            try {
                redisService.addTokenBlacklist(token.trim(), expiration / 1000);
            } catch (Exception e) {
                log.warn("Redis logout: {}", e.getMessage());
            }
        }
        return Result.ok("已退出");
    }


    /**
     * 销号
     * <p>
     * 微服务拆分说明：userId 由网关 X-User-Id 头提供（CurrentUser），不再从 token 解析。
     */
    @DeleteMapping("/delete")
    public Result<?> delete(HttpServletRequest request, String currentPassword) {
        Long userId = CurrentUser.getUserId(request);
        if (userId == null) return Result.error("请先登录");
        try {
            userService.deleteAccount(userId, currentPassword);
            return Result.ok("销号成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/AdminLogin")
    public Result<?> adminLogin(@RequestBody LoginRequest login) {
        if (login.getPassword() == null || login.getPassword().isBlank())
            return Result.error("密码不能为空");
        try {
            Map<String, Object> r = userService.adminLogin(login);
            return Result.ok("管理员登录成功", r);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

}
