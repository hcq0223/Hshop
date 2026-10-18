package com.shopai.common.config;

import io.lettuce.core.ReadFrom;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisTemplateConfig {
    @Bean
    public LettuceClientConfigurationBuilderCustomizer customizer() {
        return builder -> builder.readFrom(ReadFrom.REPLICA_PREFERRED);
    }
}
