package com.promoengine.auth.infrastructure.input.rest.impl;

import com.promoengine.auth.application.port.in.AuthService;
import com.promoengine.auth.infrastructure.input.rest.AuthController;
import com.promoengine.auth.infrastructure.input.rest.dto.request.*;
import com.promoengine.auth.infrastructure.input.rest.dto.response.TokenResponseDTO;
import com.promoengine.auth.infrastructure.input.rest.dto.response.UserResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
public class AuthControllerImpl implements AuthController {

    private final AuthService authService;

    @Override
    public ResponseEntity<TokenResponseDTO> register(RegisterRequestDTO request) {
        return null;
    }

    @Override
    public ResponseEntity<Void> verifyEmail(String token) {
        return null;
    }

    @Override
    public ResponseEntity<Void> resendVerification(ResendVerificationRequestDTO request) {
        return null;
    }

    @Override
    public ResponseEntity<TokenResponseDTO> login(LoginRequestDTO request) {
        return null;
    }

    @Override
    public ResponseEntity<TokenResponseDTO> refresh(RefreshTokenRequestDTO request) {
        return null;
    }

    @Override
    public ResponseEntity<Void> logout(RefreshTokenRequestDTO request) {
        return null;
    }

    @Override
    public ResponseEntity<Void> logoutAll(Principal principal) {
        return null;
    }

    @Override
    public ResponseEntity<UserResponseDTO> me(Principal principal) {
        return null;
    }
}
