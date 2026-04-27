package com.promoengine.auth.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.timeout:3000}")
    private int timeoutMs;

    @Value("${spring.data.redis.redisson.pool.connection-pool-size:8}")
    private int connectionPoolSize;

    @Value("${spring.data.redis.redisson.pool.connection-minimum-idle-size:2}")
    private int connectionMinimumIdleSize;

    @Value("${spring.data.redis.retry-attempts:3}")
    private int retryAttempts;

    @Value("${spring.data.redis.redisson.pool.connection-idle-timeout:10000}")
    private int idleConnectionTimeoutMs;

    @Value("${spring.data.redis.retry-interval:1500}")
    private int retryIntervalMs;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        config.setCodec(new JsonJacksonCodec(objectMapper));

        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setPassword(password.isBlank() ? null : password)
                .setTimeout(timeoutMs)
                .setConnectTimeout(timeoutMs)
                .setRetryInterval(retryIntervalMs)
                .setConnectionPoolSize(connectionPoolSize)
                .setIdleConnectionTimeout(idleConnectionTimeoutMs)
                .setRetryAttempts(retryAttempts)
                .setConnectionMinimumIdleSize(connectionMinimumIdleSize);
        return Redisson.create(config);
    }
}
