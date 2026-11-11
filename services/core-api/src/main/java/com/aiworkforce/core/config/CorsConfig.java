package com.aiworkforce.core.config;

import com.aiworkforce.core.common.logging.RequestIdFilter;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cross-origin policy for browser-based clients (Angular dev server, Electron renderer).
 *
 * <p>Only the explicitly configured origins are accepted. Credentials (cookies) are not
 * allowed: the API authenticates with an {@code Authorization} header (Sprint B1), so
 * CORS never has to expose cookie-based sessions.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT,
                RequestIdFilter.REQUEST_ID_HEADER));
        configuration.setExposedHeaders(List.of(RequestIdFilter.REQUEST_ID_HEADER, HttpHeaders.LOCATION));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
