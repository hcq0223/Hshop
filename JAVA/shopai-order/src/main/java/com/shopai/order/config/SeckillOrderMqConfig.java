package com.shopai.order.config;

import com.shopai.common.mq.MqConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 订单服务的 RabbitMQ 配置：秒杀异步下单队列（消费端）
 * <p>
 * <b>背景</b>：秒杀抢购只做 Redis 原子扣减就立即返回「排队中」，
 * 真实订单由本队列驱动创建 —— 这样抢购接口耗时与订单服务解耦，
 * 秒杀洪峰不会把订单服务打垮。
 * <p>
 * <b>为什么队列由消费端声明（而不是只在生产端）</b>：生产端（shopai-seckill）
 * 确实也声明了同一队列，但两边声明是幂等的（属性一致即可），
 * 谁先启动都能正确建出队列并绑定。消费端再声明一次的价值是：
 * 订单服务单独启动时不会因为「队列还不存在」而监听失败。
 */
@Configuration
public class SeckillOrderMqConfig {

    /**
     * 主题交换机（与其它服务共用 {@code shopai.data.exchange}）。
     * <p>
     * 与 shopai-search 的声明完全一致（durable=true, autoDelete=false），
     * RabbitMQ 对同参重复声明是幂等的，不会冲突。
     */
    @Bean
    public TopicExchange seckillOrderDataExchange() {
        return new TopicExchange(MqConstants.DATA_EXCHANGE, true, false);
    }

    /**
     * 秒杀异步下单队列。
     * <p>
     * 声明必须与生产端（{@code SeckillMqConfig.seckillOrderCreateQueue}）完全一致，
     * 否则 RabbitMQ 会报 {@code PRECONDITION_FAILED - inequivalent arg}。
     * 因此这里同样不设 TTL、并绑定同一个死信交换机 ——
     * 每条消息背后都有一个已经扣了库存的用户在等订单，不能因「等太久」被丢弃。
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
                .to(seckillOrderDataExchange())
                .with(MqConstants.ROUTING_SECKILL_ORDER_CREATE);
    }

    /**
     * 消息转换器：放行本项目的秒杀消息类，<b>同时保持反序列化白名单机制生效</b>
     * <p>
     * 生产端（秒杀服务）用 {@code rabbitTemplate.convertAndSend(message)} 发送 Java 对象，
     * Spring AMQP 默认 {@code SimpleMessageConverter} 走 JDK 序列化，消息 contentType 为
     * {@code application/x-java-serialized-object}。
     * 而 Spring AMQP 2.x 起出于安全考虑<b>默认禁止反序列化任意类</b>，不放行就会抛：
     * <pre>
     *   SecurityException: Attempt to deserialize unauthorized class
     *   com.shopai.common.dto.internal.seckill.SeckillOrderMessage
     * </pre>
     * 表现为「消息一直堆积、订单永远不生成」。
     * <p>
     * 这里只放行 {@code com.shopai.common.mq} 与 {@code com.shopai.common.dto.internal.seckill}
     * 两个包，而不是用 {@code SPRING_AMQP_DESERIALIZATION_TRUST_ALL} 全局放开 ——
     * 后者会让任何能往该交换机发消息的人构造 gadget 链执行任意代码。
     */
    @Bean
    public SimpleMessageConverter simpleMessageConverter() {
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.setAllowedListPatterns(List.of(
                "com.shopai.common.mq.*",
                "com.shopai.common.dto.internal.seckill.*"));
        return converter;
    }
}
