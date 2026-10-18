package com.shopai.seckill.mq;

import com.shopai.common.dto.internal.seckill.SeckillOrderMessage;
import com.shopai.common.mq.MqConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 秒杀下单消息投递器
 * <p>
 * 与队列声明（{@link SeckillMqConfig}）分开成两个类：
 * 配置类只负责声明拓扑，投递是有业务语义的动作（会失败、需要重试），
 * 混在配置类里会让「哪些是 Bean 定义、哪些是运行逻辑」变得含糊。
 * <p>
 * <b>投递失败为什么不吞掉</b>：调用方（{@code SeckillMessageRetryTask}）
 * 必须知道投递有没有成功，才能决定「标记为已投递」还是「增加重试次数并推迟」。
 * 这与索引同步的 {@code IndexEventPublisher}（失败只记日志）策略相反 ——
 * 索引同步失败可以靠全量重建索引兜底，而秒杀丢一条消息就是丢一个用户的订单。
 */
@Component
public class SeckillOrderMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderMessagePublisher.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 投递秒杀下单消息。
     *
     * @param message 消息体
     * @throws Exception 投递失败（由调用方决定重试策略，不在此吞掉）
     */
    public void send(SeckillOrderMessage message) throws Exception {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(
                MqConstants.DATA_EXCHANGE,
                MqConstants.ROUTING_SECKILL_ORDER_CREATE,
                message,
                msg -> {
                    // msgId 同时作为消息头与业务幂等键，方便在 RabbitMQ 控制台按此检索
                    msg.getMessageProperties().setHeader("msgId", message.getMsgId());
                    return msg;
                },
                correlationData);
        log.info("秒杀下单消息已投递, msgId={}, recordId={}, activityId={}, userId={}, correlationId={}",
                message.getMsgId(), message.getRecordId(), message.getActivityId(),
                message.getUserId(), correlationData.getId());
    }
}
