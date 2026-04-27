package com.promoengine.auth.application.port.in;

public record AuthResult(String accessToken, String refreshToken) {
}
