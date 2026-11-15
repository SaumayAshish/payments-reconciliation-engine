package com.saumay.payments.api;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Experimental OIDC resource-server wiring.
 *
 * The profile must not be treated as production-ready until the complete
 * HTTP authorization and identity-migration tests pass.
 */
@Configuration
@Profile("oidc")
public class OidcSecurityConfig {

    @Bean
    VerifiedJwtActorMapper verifiedJwtActorMapper() {
        return new VerifiedJwtActorMapper();
    }

    @Bean
    ActorContext oidcActorContext(VerifiedJwtActorMapper mapper) {
        return new SecurityContextActorContext(mapper);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    ApiSecurityErrorHandler apiSecurityErrorHandler(java.time.Clock clock) {
        return new ApiSecurityErrorHandler(clock);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    JwtDecoder jwtDecoder(
            @Value("${payments.security.issuer}") String issuer,
            @Value("${payments.security.audience}") String audience,
            @Value("${payments.security.jwk-set-uri}") String jwkSetUri) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(new TrustedJwtValidator(issuer, audience));
        return decoder;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain oidcSecurityFilterChain(
            HttpSecurity http,
            VerifiedJwtActorMapper mapper,
            ApiSecurityErrorHandler securityErrors) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(securityErrors)
                        .accessDeniedHandler(securityErrors))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/ingestions/statements",
                                "/api/v1/ingestions/ledger",
                                "/api/v1/reconciliations/runs")
                        .hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/ingestions/*",
                                "/api/v1/reconciliations/runs/*",
                                "/api/v1/reconciliations/runs/*/results")
                        .hasAnyRole("ADMIN", "ANALYST", "AUDITOR")

                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/reconciliations/runs/*/replay")
                        .hasAnyRole("ADMIN", "AUDITOR")

                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/reconciliations/runs/*/exceptions",
                                "/api/v1/reconciliations/runs/*/matches/*/unmatch")
                        .hasAnyRole("ADMIN", "TEAM_LEAD")

                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/exceptions",
                                "/api/v1/exceptions/*",
                                "/api/v1/approvals/*")
                        .hasAnyRole("ADMIN", "ANALYST", "TEAM_LEAD",
                                "CONTROLLER", "AUDITOR")

                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/exceptions/*/assign",
                                "/api/v1/exceptions/*/escalate",
                                "/api/v1/exceptions/*/unmatch")
                        .hasAnyRole("ADMIN", "TEAM_LEAD")

                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/exceptions/*/investigate",
                                "/api/v1/exceptions/*/comments",
                                "/api/v1/exceptions/*/match")
                        .hasAnyRole("ADMIN", "ANALYST", "TEAM_LEAD")

                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/exceptions/*/resolve")
                        .hasAnyRole("ANALYST", "TEAM_LEAD", "CONTROLLER")

                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/approvals/*/approve",
                                "/api/v1/approvals/*/reject")
                        .hasRole("CONTROLLER")

                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/audit/**")
                        .hasAnyRole("AUDITOR", "ADMIN")

                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/reports/workload")
                        .hasAnyRole("TEAM_LEAD", "ADMIN")

                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/reports/**")
                        .hasAnyRole("TEAM_LEAD", "AUDITOR", "ADMIN")

                        .requestMatchers(HttpMethod.PUT,
                                "/api/v1/configuration")
                        .hasRole("ADMIN")

                        .requestMatchers("/actuator/**")
                        .hasRole("ADMIN")

                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(securityErrors)
                        .accessDeniedHandler(securityErrors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            try {
                                Actor actor = mapper.map(token);
                                return new JwtAuthenticationToken(
                                        token,
                                        List.of(new SimpleGrantedAuthority(
                                                "ROLE_" + actor.role().name())),
                                        actor.id());
                            } catch (ApiException exception) {
                                throw new OAuth2AuthenticationException(
                                        new OAuth2Error("invalid_token"));
                            }
                        })));

        return http.build();
    }
}