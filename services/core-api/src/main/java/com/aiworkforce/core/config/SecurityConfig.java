package com.aiworkforce.core.config;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Central HTTP security policy: <strong>deny by default</strong>.
 *
 * <ul>
 *   <li>Stateless: no HTTP session, no cookies. Authentication will be a bearer token in the
 *       {@code Authorization} header (Sprint B1), which is why CSRF protection is disabled:
 *       browsers never attach that header automatically.</li>
 *   <li>Public endpoints are listed explicitly; everything else requires authentication.
 *       No authentication mechanism exists yet, so every business endpoint answers 401.</li>
 *   <li>401/403 responses are produced by {@code GlobalExceptionHandler}, so they have the same
 *       RFC 9457 shape as every other error.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Operational endpoints readable without authentication (no sensitive details exposed). */
    private static final String[] PUBLIC_ACTUATOR_PATHS = {"/actuator/health", "/actuator/health/**", "/actuator/info"};

    /** API documentation, public only when springdoc is enabled (development profile). */
    private static final String[] API_DOCS_PATHS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    /** Restrictive CSP for API responses (JSON only, never rendered as a document). */
    private static final String API_CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.requireExplicitSave(true))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentTypeOptions(withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(matchers(API_DOCS_PATHS)),
                                new StaticHeadersWriter("Content-Security-Policy", API_CONTENT_SECURITY_POLICY))))
                .authorizeHttpRequests(authorize -> {
                    // Error dispatches carry the original request's authorization decision.
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, PUBLIC_ACTUATOR_PATHS).permitAll();
                    if (apiDocsEnabled) {
                        authorize.requestMatchers(HttpMethod.GET, API_DOCS_PATHS).permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)));

        return http.build();
    }

    private static RequestMatcher matchers(String... patterns) {
        List<RequestMatcher> matchers = new ArrayList<>();
        for (String pattern : patterns) {
            matchers.add(PathPatternRequestMatcher.withDefaults().matcher(pattern));
        }
        return new OrRequestMatcher(matchers);
    }
}
