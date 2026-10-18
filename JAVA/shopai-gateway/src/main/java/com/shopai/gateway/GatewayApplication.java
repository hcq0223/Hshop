package com.shopai.gateway;

import com.shopai.common.security.JwtUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * ShopAi 网关启动类
 * <p>
 * 职责：
 * 1. 统一 JWT 认证（JwtAuthGlobalFilter）
 * 2. 路由转发到各业务服务（application.yml 配置，基于 Nacos 服务发现）
 * 3. 跨域处理
 * <p>
 * 注意：本模块为 WebFlux（spring-cloud-starter-gateway），
 * 不引入 servlet-web，因此不扫描 common 中依赖 servlet 的配置类。
 * 组件扫描范围限定在 com.shopai.gateway，避免误扫 common 的 WebMvcConfig 等。
 */
@SpringBootApplication(scanBasePackages = "com.shopai.gateway")
@EnableDiscoveryClient // 注册到 Nacos，并启用服务发现
@Import(JwtUtil.class)  // 显式导入 JwtUtil（位于 common，超出网关扫描范围），供 JwtAuthGlobalFilter 注入
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
