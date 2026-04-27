package com.promoengine.auth.application.port.in;

import com.promoengine.auth.domain.model.UserEntity;

public interface AuthService {

    AuthResult register(String email, String password);

    void verifyEmail(String token);

    void resendVerification(String email);

    AuthResult login(String email, String password);

    AuthResult refresh(String refreshToken);

    void logout(String refreshToken);

    void logoutAll(String userId);

    UserEntity me(String userId);
}
