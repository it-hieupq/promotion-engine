package com.promoengine.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
@ConfigurationProperties(prefix = "app.jwt")
public class JwtConfig {

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private long accessTokenExpirationMs;
    private long refreshTokenExpirationMs;
}
