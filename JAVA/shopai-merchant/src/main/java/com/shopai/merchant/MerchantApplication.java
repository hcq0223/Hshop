package com.shopai.merchant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShopAi 商家/评价服务启动类
 * <p>
 * 职责（微服务拆分后，从单体 com.example.demo.ShopAiApplication 拆出）：
 * 1. 商家入驻/审核（MerchantController / AdminMerchantController）
 * 2. 店铺/实名修改审核、提现、财务、周营收归档（MerchantService）
 * 3. 实名认证（MerchantRealNameController / MerchantRealNameService）
 * 4. 评价 + 评价投诉（ReviewController / ReviewComplaintController）
 * 5. 每周一营收归档定时任务（@EnableScheduling）
 * <p>
 * 微服务拆分说明：
 * - 商家信息/审核通过后发 MQ merchant.changed，搜索服务消费后同步 ES。
 * - 按「独立库」处理跨服务数据访问：users / orders / customers / products 表分别归属
 *   用户 / 订单 / 用户 / 商品服务，本服务不再使用 *RefMapper 直查直写，
 *   一律通过 {@code com.shopai.merchant.feign} 下的 Feign 客户端调用对方内部接口。
 * - 组件扫描：本启动类所在包 com.shopai.merchant + 公共库 com.shopai.common。
 */
@SpringBootApplication(scanBasePackages = {"com.shopai.merchant", "com.shopai.common"})
@EnableDiscoveryClient // 注册到 Nacos
@EnableFeignClients(basePackages = "com.shopai.merchant.feign") // 启用 Feign 客户端（调用户/订单/商品服务）
@EnableScheduling // 启用周营收归档定时任务
@MapperScan("com.shopai.merchant.mapper")
public class MerchantApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantApplication.class, args);
    }
}
