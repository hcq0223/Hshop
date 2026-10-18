package com.shopai.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 安全配置（微服务拆分后下沉到公共库）
 * <p>
 * 微服务拆分说明（网关统一认证模式）：
 * 原单体的 SecurityConfig 三层权限（公开/需登录/管理员）+ JwtAuthenticationFilter 验签，
 * 在拆分后由「网关(shopai-gateway)」统一承担：网关解析 JWT、校验黑名单、写入
 * X-User-Id / X-User-Role / X-User-Phone 头，业务服务不再各自验签。
 * <p>
 * 因此各业务服务的过滤链统一「放行所有请求」（anyRequest().permitAll()），
 * 真正的鉴权在网关完成；需要细粒度控制的接口，可在 Controller 上标注 @PreAuthorize 兜底。
 * <p>
 * 同时保留 PasswordEncoder Bean：用户服务（登录/注册/改密/重置密码）需要 BCrypt 加密。
 * <p>
 * 注意：网关(WebFlux)不扫描 com.shopai.common 包（scanBasePackages 限定为 com.shopai.gateway），
 * 因此本类不会被网关加载；仅被引入 servlet-web 的业务服务通过组件扫描装配。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // 开启 @PreAuthorize，供 Controller 层做细粒度兜底鉴权
public class SecurityConfig {

    /**
     * BCrypt 密码加密器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤链（微服务模式：放行所有，鉴权交给网关）
     * <p>
     * - 关闭 CSRF（前后端分离 + 网关认证，无需 CSRF 保护）
     * - 无状态会话（不创建 HttpSession）
     * - 所有请求放行（网关已统一鉴权）
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 关闭 CSRF
                .csrf(csrf -> csrf.disable())
                // 无状态会话
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 放行所有请求（网关统一鉴权）
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
