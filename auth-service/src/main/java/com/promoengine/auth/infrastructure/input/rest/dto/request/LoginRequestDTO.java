package com.promoengine.auth.infrastructure.input.rest.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequestDTO {

    @NotBlank
    @Email
    String email;

    @NotBlank
    String password;
}
