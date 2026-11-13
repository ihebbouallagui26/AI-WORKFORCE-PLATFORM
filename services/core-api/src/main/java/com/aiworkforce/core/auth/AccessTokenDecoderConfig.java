package com.aiworkforce.core.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JWT decoder for the resource server. Signature verification is delegated to Nimbus
 * (maintained library, keys fetched from the JWKS endpoint and cached); claims are checked
 * by {@link AccessTokenValidators}. No cryptography is implemented by hand.
 *
 * <p>The JWKS is fetched lazily on the first request, so the API starts even if the identity
 * provider is temporarily unavailable (requests then fail with 401 until it is reachable).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcProperties.class)
public class AccessTokenDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(OidcProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(AccessTokenValidators.forProperties(properties));
        return decoder;
    }
}
