package com.promoengine.auth.config;

import com.promoengine.auth.config.properties.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@Slf4j
public class JwtConfig {

    @Bean
    public RSAPrivateKey rsaPrivateKey(@Value("${app.jwt.private-key}") Resource rsaPrivateKey) {
        try (InputStream inputStream = rsaPrivateKey.getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(inputStream);
        } catch (IOException e) {
            log.error("Error reading private key: {}", e.getMessage(), e);
            return null;
        }
    }

    @Bean
    RSAPublicKey publicKey(@Value("${app.jwt.public-key}") Resource rsaPublicKey) {
        try(InputStream inputStream = rsaPublicKey.getInputStream()) {
            return RsaKeyConverters.x509().convert(inputStream);
        } catch (IOException e) {
            log.error("Error reading public key: {}", e.getMessage(), e);
            return null;
        }
    }
}