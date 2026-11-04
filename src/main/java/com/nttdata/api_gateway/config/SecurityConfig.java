package com.nttdata.api_gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Seguridad centralizada: el gateway valida el JWT emitido por auth-service y autoriza por rol.
 * Los microservicios de negocio no publican puerto, así que solo se llega a ellos por aquí.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String EXECUTIVE = "EXECUTIVE";

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        // públicas
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers("/actuator/health/**").permitAll()
                        // auth-service
                        .pathMatchers("/api/auth/users/**").hasRole(ADMIN)
                        .pathMatchers("/api/auth/**").authenticated()        // logout, me, cambiar contraseña
                        // borrar/cerrar: solo ADMIN
                        .pathMatchers(HttpMethod.DELETE, "/api/customers/**", "/api/accounts/**",
                                "/api/credits/**", "/api/credit-cards/**").hasRole(ADMIN)
                        // operación diaria del banco: ADMIN y EXECUTIVE
                        .pathMatchers("/api/customers/**", "/api/accounts/**", "/api/credits/**",
                                "/api/credit-cards/**", "/api/transactions/**", "/api/reports/**")
                        .hasAnyRole(ADMIN, EXECUTIVE)
                        // cualquier otra ruta: denegada (más seguro que permitir por defecto)
                        .anyExchange().denyAll())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Verifica firma (HS256), expiración y emisor; rechaza los tokens revocados en el logout.
     * La clave y el prefijo de Redis deben ser los mismos que usa auth-service.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(@Value("${bank.security.jwt.secret}") String secret,
                                         @Value("${bank.security.jwt.issuer:bank-auth-service}") String issuer,
                                         ReactiveStringRedisTemplate redis) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 caracteres");
        }
        SecretKey secretKey = new SecretKeySpec(key, "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return token -> decoder.decode(token)
                .flatMap(jwt -> redis.hasKey("revoked-token:" + jwt.getId())
                        .flatMap(revoked -> Boolean.TRUE.equals(revoked)
                                ? Mono.<Jwt>error(new BadJwtException("El token fue revocado"))
                                : Mono.just(jwt)));
    }

    /** El claim "roles" se convierte en autoridades ROLE_ADMIN, ROLE_EXECUTIVE... */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

}
