package com.shopai.inventory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * ShopAi 库存服务启动类
 * <p>
 * 职责（微服务拆分后，从单体拆出）：
 * 1. 库存查询（单个/批量）、扣减、回补、秒杀、预热
 * 2. 对接独立 inventory 表（乐观锁扣减防超卖）
 * 3. 作为 Seata AT 分支事务，被订单服务通过 Feign 调用（deduct/restore）
 * <p>
 * 组件扫描：本启动类所在包 com.shopai.inventory + 公共库 com.shopai.common。
 * 已承接：单体 ProductSkuService 中全部库存相关逻辑（扣减/回补/秒杀/预热）。
 */
@SpringBootApplication(scanBasePackages = {"com.shopai.inventory", "com.shopai.common"})
@EnableDiscoveryClient // 注册到 Nacos
@MapperScan("com.shopai.inventory.mapper")
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
