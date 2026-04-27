package com.promoengine.auth.infrastructure.input.rest.dto.response;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TokenResponseDTO {

    String accessToken;
    String refreshToken;
    String tokenType;
    long expiresIn;
}
