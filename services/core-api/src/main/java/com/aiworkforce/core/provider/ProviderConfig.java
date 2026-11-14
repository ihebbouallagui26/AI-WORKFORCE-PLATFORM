package com.aiworkforce.core.provider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecretsProperties.class, ProviderProperties.class})
class ProviderConfig {
}
