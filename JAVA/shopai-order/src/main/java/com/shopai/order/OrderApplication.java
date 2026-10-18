package com.shopai.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShopAi 订单服务启动类
 * <p>
 * 职责（微服务拆分后，从单体 com.example.demo.ShopAiApplication 拆出）：
 * 1. 购物车（CartController / ShoppingCartService）
 * 2. 收货地址（AddressController / AddressService）
 * 3. 订单状态机 + 退款（OrderController / OrderService）
 * 4. 自动收货/自动完成定时任务（@EnableScheduling）
 * <p>
 * 微服务拆分说明：
 * - 下单/退款通过 Seata @GlobalTransactional 全局事务，Feign 调 shopai-inventory 扣减/回补库存，
 *   调 shopai-product 内部接口累销量。
 * - 组件扫描：本启动类所在包 com.shopai.order + 公共库 com.shopai.common。
 */
@SpringBootApplication(scanBasePackages = {"com.shopai.order", "com.shopai.common"})
@EnableDiscoveryClient // 注册到 Nacos
@EnableFeignClients(basePackages = "com.shopai.order.feign") // 启用 Feign 客户端（调库存/商品服务）
@EnableScheduling // 启用自动收货/自动完成定时任务
@MapperScan("com.shopai.order.mapper")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
