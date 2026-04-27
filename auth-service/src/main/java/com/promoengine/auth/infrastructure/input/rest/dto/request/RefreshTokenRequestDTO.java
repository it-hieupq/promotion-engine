package com.promoengine.auth.infrastructure.input.rest.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenRequestDTO {

    @NotBlank
    String refreshToken;
}
