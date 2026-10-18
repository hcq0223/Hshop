package com.shopai.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * ShopAi 用户服务启动类
 * <p>
 * 职责（微服务拆分后，从单体 com.example.demo.ShopAiApplication 拆出）：
 * 1. 认证：登录/注册/忘记密码/改密/退出/销号
 * 2. 客户资料：新增/更新/查询
 * 3. 管理员用户管理：用户列表/详情/客户列表/状态/销号/重置密码
 * 4. 管理员仪表盘统计（商品数/订单数/趋势/商家销售额排名 → Feign 调商品/订单/商家服务）
 * <p>
 * 组件扫描：默认扫描本启动类所在包 com.shopai.user 及其子包；
 * 公共库(com.shopai.common)中的 Result/JwtUtil/RedisService/GlobalExceptionHandler/
 * SecurityConfig/MyBatisPlusConfig/WebMvcConfig/SchedulerConfig 等通过
 * 默认扫描 + @MapperScan 装配。注意：网关(WebFlux)不会扫描本类。
 * <p>
 * 微服务拆分说明：按「独立库」处理跨服务数据访问后，本服务不再直查 products / orders /
 * merchants / customers 等外部库表，改为通过 {@code com.shopai.user.feign} 下的
 * Feign 客户端调用各服务的内部接口。
 */
@SpringBootApplication(scanBasePackages = {"com.shopai.user", "com.shopai.common"})
@EnableDiscoveryClient // 注册到 Nacos
@EnableFeignClients(basePackages = "com.shopai.user.feign") // 启用 Feign 客户端（调商品/订单/商家服务）
@MapperScan("com.shopai.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}


