package com.promoengine.auth.infrastructure.input.rest.dto.response;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Getter
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserResponseDTO {

    String id;
    String email;
    String role;
    String status;
    LocalDateTime createdAt;
}
