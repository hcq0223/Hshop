package com.shopai.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * ShopAi 搜索服务启动类
 * <p>
 * 职责（微服务拆分后新增）：
 * 1. 商品搜索、店铺内搜索、商家搜索（全部走 Elasticsearch）
 * 2. 消费 RabbitMQ 的 product.changed / merchant.changed 事件，增量同步 ES
 * 3. 提供全量重建索引接口（POST /api/search/rebuild）
 * <p>
 * 组件扫描：本启动类所在包 com.shopai.search + 公共库 com.shopai.common。
 * 注意：本服务不开启 @EnableScheduling。
 * <p>
 * 微服务拆分说明（独立库）：本服务不再直接连数据库读取索引源数据。
 * 源数据（products / merchants 及其关联的店铺名、分类名、账号创建时间）一律通过
 * {@code com.shopai.search.feign} 下的 Feign 客户端调用商品服务 / 商家服务的内部接口拉取，
 * 因此本服务不再保留 *RefMapper / *Ref 实体，也不扫描任何 Mapper。
 */
@SpringBootApplication(scanBasePackages = {"com.shopai.search", "com.shopai.common"})
@EnableDiscoveryClient // 注册到 Nacos
@EnableFeignClients(basePackages = "com.shopai.search.feign") // 启用 Feign 客户端（拉取索引源数据）
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
