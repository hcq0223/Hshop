package com.shopai.search.config;

import com.shopai.common.mq.MqConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RabbitMQ 声明式配置（搜索服务内，消费端负责声明交换机/队列/绑定）
 * <p>
 * 微服务拆分说明：商品、商家、订单、评价、用户五个服务作为生产者，通过
 * {@code rabbitTemplate.convertAndSend} 向主题交换机 {@code shopai.data.exchange}
 * 发送索引变更事件。本服务作为消费者，在此声明交换机、队列与绑定关系，
 * 保证即使消费者先于生产者启动，也能正确创建队列并接收消息。
 * <p>
 * 绑定策略（列表查询改造）：统一使用 topic 通配绑定 {@code <entity>.changed.#}，
 * 这样 {@code <entity>.changed}（upsert）与 {@code <entity>.changed.delete}（删除）
 * 会进入同一队列，由消费者按 {@code operation} 字段分流。好处是后续新增子类型
 * （如 {@code order.changed.refund}）无需修改绑定关系。
 * <p>
 * 可靠性（见设计文档第 10 节）：消息持久化（durable）、消费者手动 ack、
 * 失败进死信队列；全量重建接口作为最终一致性兜底。
 */
@Configuration
public class RabbitMqConfig {

    // 队列名统一用公共库常量，避免生产端/消费端各写一份
    public static final String PRODUCT_CHANGED_QUEUE = MqConstants.QUEUE_PRODUCT_CHANGED;
    public static final String MERCHANT_CHANGED_QUEUE = MqConstants.QUEUE_MERCHANT_CHANGED;
    public static final String ORDER_CHANGED_QUEUE = MqConstants.QUEUE_ORDER_CHANGED;
    public static final String REVIEW_CHANGED_QUEUE = MqConstants.QUEUE_REVIEW_CHANGED;
    public static final String USER_CHANGED_QUEUE = MqConstants.QUEUE_USER_CHANGED;
    public static final String DLX_QUEUE = MqConstants.QUEUE_DLX;

    /**
     * 主题交换机（与生产者共用同一交换机名）
     */
    @Bean
    public TopicExchange dataExchange() {
        return new TopicExchange(MqConstants.DATA_EXCHANGE);
    }

    // ==================== 消息转换器（反序列化白名单）====================

    /**
     * 消息转换器：放行本项目自己的事件类，<b>同时保持反序列化白名单机制生效</b>
     * <p>
     * <b>为什么必须配这个 Bean</b>：生产端（{@code IndexEventPublisher}）用
     * {@code rabbitTemplate.convertAndSend(event)} 发送的是 Java 对象，
     * Spring AMQP 默认的 {@code SimpleMessageConverter} 会走 JDK 序列化，
     * 消息 contentType 为 {@code application/x-java-serialized-object}。
     * <p>
     * 而 Spring AMQP 2.x 起，{@code AllowedListDeserializingMessageConverter}
     * 出于安全考虑<b>默认禁止反序列化任意类</b>（防反序列化 gadget 攻击），
     * 于是消费端会抛：
     * <pre>
     *   SecurityException: Attempt to deserialize unauthorized class
     *   com.shopai.common.mq.IndexChangedEvent
     * </pre>
     * 表现为：消息被投到队列 → 消费立即失败 → 重试 3 次耗尽 → 进死信队列，
     * <b>ES 索引一条都不会更新</b>（列表页看不到新建的数据，但数据库里一切正常）。
     * <p>
     * <b>为什么不用 {@code SPRING_AMQP_DESERIALIZATION_TRUST_ALL}</b>：
     * 那会把整个 JDK 反序列化面全部打开，任何能往该交换机发消息的人都能构造
     * gadget 链执行任意代码。这里只放行 {@code com.shopai.common.mq} 这一个包 ——
     * 正好覆盖 {@link com.shopai.common.mq.IndexChangedEvent} 及其可能的嵌套类，
     * 其余类仍然被拒。安全性远好于全局放开。
     * <p>
     * 注意：本 Bean 生效需要消费端不显式指定其它 converter。
     * {@code @RabbitListener} 会自动使用容器里唯一的 {@code MessageConverter}。
     */
    @Bean
    public SimpleMessageConverter simpleMessageConverter() {
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.setAllowedListPatterns(List.of("com.shopai.common.mq.*"));
        return converter;
    }

    // ==================== 队列声明 ====================

    @Bean
    public Queue productChangedQueue() {
        return searchQueue(PRODUCT_CHANGED_QUEUE);
    }

    @Bean
    public Queue merchantChangedQueue() {
        return searchQueue(MERCHANT_CHANGED_QUEUE);
    }

    @Bean
    public Queue orderChangedQueue() {
        return searchQueue(ORDER_CHANGED_QUEUE);
    }

    @Bean
    public Queue reviewChangedQueue() {
        return searchQueue(REVIEW_CHANGED_QUEUE);
    }

    @Bean
    public Queue userChangedQueue() {
        return searchQueue(USER_CHANGED_QUEUE);
    }

    /**
     * 统一的索引同步队列定义
     * <p>
     * 说明：与原实现相比去掉了 {@code .ttl(10000)} —— 原来给索引同步队列设了 10 秒 TTL，
     * 会在消费积压（如全量重建期间、ES 抖动时）把未消费的消息直接判为过期丢进死信队列，
     * 导致索引静默缺数据。索引同步消息本身很小，保留即可，不应设过期时间。
     */
    private Queue searchQueue(String name) {
        return QueueBuilder
                .durable(name)
                .lazy()
                .deadLetterExchange(MqConstants.DATA_DLX)
                .deadLetterRoutingKey(MqConstants.ROUTING_DLX)
                .build();
    }

    // ==================== 绑定（topic 通配，同时接收 upsert 与 delete） ====================

    @Bean
    public Binding productChangedBinding() {
        return bind(productChangedQueue(), MqConstants.ROUTING_PRODUCT_CHANGED);
    }

    @Bean
    public Binding merchantChangedBinding() {
        return bind(merchantChangedQueue(), MqConstants.ROUTING_MERCHANT_CHANGED);
    }

    @Bean
    public Binding orderChangedBinding() {
        return bind(orderChangedQueue(), MqConstants.ROUTING_ORDER_CHANGED);
    }

    @Bean
    public Binding reviewChangedBinding() {
        return bind(reviewChangedQueue(), MqConstants.ROUTING_REVIEW_CHANGED);
    }

    @Bean
    public Binding userChangedBinding() {
        return bind(userChangedQueue(), MqConstants.ROUTING_USER_CHANGED);
    }

    /** 绑定到 {@code <base>.#}，覆盖 base 本身与 base.delete 等子路由键 */
    private Binding bind(Queue queue, String baseRoutingKey) {
        return BindingBuilder.bind(queue)
                .to(dataExchange())
                .with(baseRoutingKey + ".#");
    }

    // ==================== 死信 ====================

    /**
     * 死信交换机
     */
    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange(MqConstants.DATA_DLX);
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange())
                .with(MqConstants.ROUTING_DLX);
    }
}
