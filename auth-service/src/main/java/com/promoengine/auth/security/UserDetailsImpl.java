package com.promoengine.auth.security;

import com.promoengine.auth.entity.UserEntity;
import com.promoengine.auth.entity.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

import static com.promoengine.auth.constant.Constants.ROLE;

@RequiredArgsConstructor
public class UserDetailsImpl implements UserDetails {

    private final UserEntity userEntity;

    public static UserDetailsImpl build(UserEntity userEntity) {
        return new UserDetailsImpl(userEntity);
    }

    public long getUserId() {
        return userEntity.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singleton(new SimpleGrantedAuthority( ROLE.concat(userEntity.getRole().name()) ));
    }

    @Override
    public String getPassword() {
        return userEntity.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return userEntity.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return userEntity.getStatus() != UserStatus.BLOCKED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return userEntity.getStatus() != UserStatus.DELETED;
    }

}
