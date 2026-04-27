package com.promoengine.auth.infrastructure.output.external;

import com.promoengine.auth.application.port.out.TokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisTokenAdapter implements TokenPort {

    private static final String REFRESH_TOKEN_KEY = "rt:%s:%s";
    private static final String EMAIL_VERIFY_KEY  = "email:verify:%s";

    private final RedisService redisService;

    @Override
    public void saveRefreshToken(String userId, String tokenId, long ttlSeconds) {
        redisService.set(String.format(REFRESH_TOKEN_KEY, userId, tokenId), userId, ttlSeconds);
    }

    @Override
    public Optional<String> findRefreshToken(String userId, String tokenId) {
        return redisService.get(String.format(REFRESH_TOKEN_KEY, userId, tokenId), String.class);
    }

    @Override
    public void deleteRefreshToken(String userId, String tokenId) {
        redisService.delete(String.format(REFRESH_TOKEN_KEY, userId, tokenId));
    }

    @Override
    public void deleteAllRefreshTokens(String userId) {
        redisService.deleteByPattern(String.format("rt:%s:*", userId));
    }

    @Override
    public void saveEmailVerificationToken(String token, String userId, long ttlSeconds) {
        redisService.set(String.format(EMAIL_VERIFY_KEY, token), userId, ttlSeconds);
    }

    @Override
    public Optional<String> findEmailVerificationToken(String token) {
        return redisService.get(String.format(EMAIL_VERIFY_KEY, token), String.class);
    }

    @Override
    public void deleteEmailVerificationToken(String token) {
        redisService.delete(String.format(EMAIL_VERIFY_KEY, token));
    }
}
