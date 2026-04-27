package com.promoengine.auth.application.port.in.impl;

import com.promoengine.auth.application.port.in.AuthResult;
import com.promoengine.auth.application.port.in.AuthService;
import com.promoengine.auth.domain.model.UserEntity;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    @Override
    public AuthResult register(String email, String password) {
        return null;
    }

    @Override
    public void verifyEmail(String token) {

    }

    @Override
    public void resendVerification(String email) {

    }

    @Override
    public AuthResult login(String email, String password) {
        return null;
    }

    @Override
    public AuthResult refresh(String refreshToken) {
        return null;
    }

    @Override
    public void logout(String refreshToken) {

    }

    @Override
    public void logoutAll(String userId) {

    }

    @Override
    public UserEntity me(String userId) {
        return null;
    }
}
