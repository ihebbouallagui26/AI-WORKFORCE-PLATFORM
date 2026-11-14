package com.aiworkforce.core.identity;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link AuthenticatedUserArgumentResolver} in Spring MVC. */
@Configuration(proxyBeanMethods = false)
public class IdentityWebConfig implements WebMvcConfigurer {

    private final AuthenticatedUserArgumentResolver resolver;

    public IdentityWebConfig(AuthenticatedUserArgumentResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
    }
}
