package com.saumay.payments.api;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Denies HTTP access when neither OIDC nor the temporary development
 * compatibility profile is active.
 */
@Configuration
@Profile("!dev & !oidc")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DefaultDenySecurityConfig {

    @Bean
    SecurityFilterChain defaultDenySecurityFilterChain(
            HttpSecurity http, Clock clock) throws Exception {

        ApiSecurityErrorHandler errors = new ApiSecurityErrorHandler(clock);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(errors))
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().denyAll());

        return http.build();
    }
}