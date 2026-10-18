package com.shopai.seckill.mq;

import com.shopai.common.mq.MqConstants;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀异步下单的 RabbitMQ 配置（生产端）
 * <p>
 * <b>队列由谁声明</b>：本服务作为生产者，在这里声明交换机/队列/绑定。
 * 通常「谁消费谁声明」更常见，但秒杀场景下生产者先上线、订单服务后跟上的
 * 顺序更常见，生产端声明可以保证「消息发出去时队列一定存在」——
 * 否则消息会因为路由不到队列而被直接丢弃（topic 交换机无匹配队列时静默丢弃），
 * 表现为「抢到了但订单永远不出现」。
 * <p>
 * <b>与索引同步队列分开</b>：本队列是业务队列，积压直接影响用户能否拿到订单，
 * 需要独立监控与扩容；混在索引同步队列里排查困难（见 MqConstants 的注释）。
 * <p>
 * <b>死信</b>：复用公共库已有的 {@code shopai.dlx.exchange}。消息重试耗尽后
 * 进入死信队列，由运维介入；同时秒杀侧的本地消息表也会把该笔记录标记为失败
 * 并回补库存，两条兜底路径保证不会卡在「库存扣了、订单没有」的中间态。
 */
@Configuration
public class SeckillMqConfig {

    /**
     * 主题交换机（与其它服务共用同一个数据交换机）。
     */
    @Bean
    public TopicExchange seckillDataExchange() {
        return new TopicExchange(MqConstants.DATA_EXCHANGE, true, false);
    }

    /**
     * 秒杀下单队列。
     * <p>
     * 不设 TTL：订单创建消息积压时绝不能因为「等太久」被丢弃 ——
     * 每条消息背后都有一个已经扣了库存的用户在等订单。
     */
    @Bean
    public Queue seckillOrderCreateQueue() {
        return QueueBuilder
                .durable(MqConstants.QUEUE_SECKILL_ORDER_CREATE)
                .deadLetterExchange(MqConstants.DATA_DLX)
                .deadLetterRoutingKey(MqConstants.ROUTING_DLX)
                .build();
    }

    @Bean
    public Binding seckillOrderCreateBinding() {
        return BindingBuilder.bind(seckillOrderCreateQueue())
                .to(seckillDataExchange())
                .with(MqConstants.ROUTING_SECKILL_ORDER_CREATE);
    }
}
