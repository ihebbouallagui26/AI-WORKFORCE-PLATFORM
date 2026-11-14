package com.aiworkforce.core.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * Validation rules applied to every bearer token, on top of the signature check done by the
 * decoder (Nimbus, RS256 only). All of them must pass:
 *
 * <ul>
 *   <li>{@code iss} equals the configured issuer exactly;</li>
 *   <li>{@code exp} is in the future and {@code nbf} (if present) in the past, 30 s clock skew;</li>
 *   <li>{@code aud} contains the API audience — ID tokens (audience = the desktop client) fail here;</li>
 *   <li>{@code typ} is {@code Bearer}: Keycloak marks ID tokens {@code ID} and refresh tokens
 *       {@code Refresh}, so they are rejected even if an audience were misconfigured;</li>
 *   <li>{@code sub} is present (identity key together with {@code iss}).</li>
 * </ul>
 */
public final class AccessTokenValidators {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    static final String TOKEN_TYPE_CLAIM = "typ";
    static final String ACCESS_TOKEN_TYPE = "Bearer";

    private AccessTokenValidators() {
    }

    public static OAuth2TokenValidator<Jwt> forProperties(OidcProperties properties) {
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<String>(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE::equals),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank()));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
