package com.promoengine.auth.infrastructure.output.external;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Optional;

public interface RedisService {

    <T> void set(String key, T value, long ttlSeconds);

    <T> Optional<T> get(String key, Class<T> type);

    <T> Optional<T> get(String key, TypeReference<T> typeRef);

    void delete(String key);

    void deleteByPattern(String pattern);
}
