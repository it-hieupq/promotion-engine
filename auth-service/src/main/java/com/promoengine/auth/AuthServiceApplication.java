package com.promoengine.auth;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

    @Bean
    ApplicationRunner verifyKeys(RSAPrivateKey priv, RSAPublicKey pub) {
        return args -> {
            System.out.println("Private key algo: " + priv.getAlgorithm());
            System.out.println("Public key algo: " + pub.getAlgorithm());
        };
    }

}
