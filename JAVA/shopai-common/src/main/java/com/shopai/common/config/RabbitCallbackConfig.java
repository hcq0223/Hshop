package com.shopai.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitCallbackConfig {
    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer(
            CachingConnectionFactory connectionFactory) {

        // 1. 开启 publisher returns（退回机制的总开关）
        connectionFactory.setPublisherReturns(true);

        // 2. 开启 publisher confirm（确认机制）
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED);

        return template -> {
            // 3. 开启 mandatory（发送时带 mandatory 标志）
            template.setMandatory(true);

            // 4. 注册确认回调
            template.setConfirmCallback((correlationData, ack, cause) -> {
                String id = correlationData != null ? correlationData.getId() : "null";
                if (ack) {
                    log.info("消息到达交换机, correlationDataId={}", id);
                } else {
                    log.error("消息未到达交换机, correlationDataId={}, cause={}", id, cause);
                }
            });

            // 5. 注册退回回调
            template.setReturnsCallback(returned -> {
                log.error("消息路由失败退回: exchange={}, routingKey={}, replyCode={}, replyText={}, correlationDataId={}",
                        returned.getExchange(),
                        returned.getRoutingKey(),
                        returned.getReplyCode(),
                        returned.getReplyText(),
                        returned.getMessage().getMessageProperties().getHeader("msgId"));
            });
        };
    }
}