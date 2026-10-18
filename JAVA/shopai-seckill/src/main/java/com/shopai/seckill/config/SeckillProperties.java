package com.shopai.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀业务参数（对应 application.yml 的 {@code seckill.*}）
 */
@Data
@Component
@ConfigurationProperties(prefix = "seckill")
public class SeckillProperties {

    /**
     * 抢购结果在 Redis 中的保留时长（秒）。
     */
    private long resultTtlSeconds = 1800;

    /**
     * 活动开始前多久自动预热库存到 Redis（秒）。默认提前 10 分钟。
     */
    private long preheatAheadSeconds = 600;

    /** 本地消息表最大重试次数，超过则回补库存并置 failed */
    private int messageMaxRetry = 5;

    /** 定时任务每批处理条数，避免一次扫全表 */
    private int taskBatchSize = 200;

    /**
     * 抢购记录多久没成单就判定为超时并回补库存（秒）。默认 15 分钟。
     */
    private long orderTimeoutSeconds = 900;
}
