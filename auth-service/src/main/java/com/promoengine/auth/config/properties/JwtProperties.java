package com.promoengine.auth.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;


@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(Duration accessTokenTtl,
                            Duration refreshTokenTtl
) {
}

