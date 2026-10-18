package com.shopai.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShopAi 秒杀服务启动类
 * <p>
 * 职责：
 * <ol>
 *   <li><b>活动管理</b>：商家创建/上下线秒杀活动，管理员审核与监管；</li>
 *   <li><b>独立秒杀库存</b>：{@code seckill_stock} 表按「活动 × SKU」维护独立额度，
 *       <b>不与 shopai-inventory 的 inventory 表联动</b>（见设计文档「库存模型」一节）；</li>
 *   <li><b>高并发抢购</b>：活动开始前把库存预热到 Redis，抢购走 Lua 脚本原子扣减，
 *       把绝大部分流量挡在数据库之前；</li>
 *   <li><b>异步下单</b>：扣减成功后写本地消息表，经 RabbitMQ 异步通知订单服务创建真实订单；</li>
 *   <li><b>最终一致性</b>：定时任务负责 预热 / 活动状态流转 / 消息重投 / 超时未成单回补库存。</li>
 * </ol>
 * <p>
 * 组件扫描：本启动类所在包 {@code com.shopai.seckill} + 公共库 {@code com.shopai.common}
 * （公共库提供 Result / CurrentUser / RedisService / GlobalExceptionHandler 等）。
 * <p>
 * 为什么开启 {@link EnableScheduling}：秒杀服务有 4 个必须的定时任务
 * （见 {@code com.shopai.seckill.task}），与 shopai-search 不同，本服务不能省。
 * <p>
 * 为什么不需要 Seata：秒杀库存扣减在 Redis（无事务），订单创建在订单服务（远程调用），
 * 两者本就不在一个本地事务里，用分布式事务也包不住 Redis 操作。
 * 一致性改由「本地消息表 + 定时补偿」保证，见 {@code SeckillMessageService}。
 */
@SpringBootApplication(scanBasePackages = {"com.shopai.seckill", "com.shopai.common"})
@EnableDiscoveryClient // 注册到 Nacos
@EnableFeignClients(basePackages = "com.shopai.seckill.feign") // 复用商品/订单服务
@EnableScheduling // 预热、状态流转、消息重投、超时回补
@MapperScan("com.shopai.seckill.mapper")
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
