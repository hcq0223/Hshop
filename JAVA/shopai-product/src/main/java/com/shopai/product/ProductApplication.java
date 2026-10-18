package com.shopai.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * ShopAi 商品服务启动类
 * <p>
 * 职责（微服务拆分后，从单体拆出）：
 * 1. 商品（SPU）/分类/SKU 基本信息增删改查、热销榜
 * 2. SKU 查询时通过 Feign 调库存服务批量补库存字段
 * 3. 商品增删改后发 MQ product.changed（搜索服务消费同步 ES）
 * <p>
 * 组件扫描：本启动类所在包 com.shopai.product + 公共库 com.shopai.common。
 * 已剥离：LIKE 搜索（→ shopai-search）、库存扣减/秒杀/预热（→ shopai-inventory）。
 */
@SpringBootApplication(scanBasePackages = {"com.shopai.product", "com.shopai.common"})
@EnableDiscoveryClient // 注册到 Nacos
@EnableFeignClients(basePackages = "com.shopai.product.feign") // 启用 Feign 客户端（调库存服务）
@MapperScan("com.shopai.product.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
