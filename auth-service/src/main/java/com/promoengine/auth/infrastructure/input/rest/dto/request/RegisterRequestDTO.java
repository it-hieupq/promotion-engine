package com.promoengine.auth.infrastructure.input.rest.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequestDTO {

    @NotBlank
    @Email
    String email;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password;
}
