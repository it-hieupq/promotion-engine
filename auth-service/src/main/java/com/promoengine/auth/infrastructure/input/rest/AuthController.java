package com.promoengine.auth.infrastructure.input.rest;

import com.promoengine.auth.infrastructure.input.rest.dto.request.*;
import com.promoengine.auth.infrastructure.input.rest.dto.response.TokenResponseDTO;
import com.promoengine.auth.infrastructure.input.rest.dto.response.UserResponseDTO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RequestMapping("/api/v1/auth")
public interface AuthController {

    @PostMapping("/register")
    ResponseEntity<TokenResponseDTO> register(@Valid @RequestBody RegisterRequestDTO request);

    @GetMapping("/verify-email")
    ResponseEntity<Void> verifyEmail(@RequestParam String token);

    @PostMapping("/resend-verification")
    ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequestDTO request);

    @PostMapping("/login")
    ResponseEntity<TokenResponseDTO> login(@Valid @RequestBody LoginRequestDTO request);

    @PostMapping("/refresh")
    ResponseEntity<TokenResponseDTO> refresh(@Valid @RequestBody RefreshTokenRequestDTO request);

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequestDTO request);

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(Principal principal);

    @GetMapping("/me")
    ResponseEntity<UserResponseDTO> me(Principal principal);
}
