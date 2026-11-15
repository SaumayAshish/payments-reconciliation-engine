package com.saumay.payments.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Temporary compatibility with the pre-Phase-8 HTTP behavior.
 *
 * This is not an authentication configuration. It exists only while the
 * existing HTTP tests and deployment setup are migrated to the oidc profile.
 */
@Configuration
@Profile("dev & !oidc")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LegacySecurityConfig {

    @Bean
    SecurityFilterChain legacySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .anonymous(anonymous -> anonymous.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }
}