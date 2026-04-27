package com.promoengine.auth.infrastructure.output.external.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promoengine.auth.infrastructure.output.external.RedisService;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisServiceImpl implements RedisService {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    @Override
    public <T> void set(String key, T value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redissonClient.<String>getBucket(key, StringCodec.INSTANCE)
                    .set(json, Duration.of(ttlSeconds, ChronoUnit.SECONDS));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize value for key: {}", key, e);
            throw new RuntimeException("Redis set failed for key: " + key, e);
        }
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redissonClient.<String>getBucket(key, StringCodec.INSTANCE).get();
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize value for key: {}", key, e);
            throw new RuntimeException("Redis get failed for key: " + key, e);
        }
    }

    @Override
    public <T> Optional<T> get(String key, TypeReference<T> typeRef) {
        try {
            String json = redissonClient.<String>getBucket(key, StringCodec.INSTANCE).get();
            return Optional.ofNullable(objectMapper.readValue(json, typeRef));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize value for key: {}", key, e);
            throw new RuntimeException("Redis get failed for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        redissonClient.getBucket(key, StringCodec.INSTANCE).delete();
    }

    @Override
    public void deleteByPattern(String pattern) {
        long deleted = redissonClient.getKeys().deleteByPattern(pattern);
        log.debug("Deleted {} keys matching pattern: {}", deleted, pattern);
    }
}
