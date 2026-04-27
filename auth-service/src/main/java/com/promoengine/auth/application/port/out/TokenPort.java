package com.promoengine.auth.application.port.out;

import java.util.Optional;

public interface TokenPort {

    // Refresh token
    void saveRefreshToken(String userId, String tokenId, long ttlSeconds);

    Optional<String> findRefreshToken(String userId, String tokenId);

    void deleteRefreshToken(String userId, String tokenId);

    void deleteAllRefreshTokens(String userId);

    // Email verification token
    void saveEmailVerificationToken(String token, String userId, long ttlSeconds);

    Optional<String> findEmailVerificationToken(String token);

    void deleteEmailVerificationToken(String token);
}
