package com.promoengine.auth.application.port.out;

import com.promoengine.auth.domain.model.UserEntity;

import java.util.Optional;

public interface UserPort {

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findById(String id);

    UserEntity save(UserEntity user);
}
