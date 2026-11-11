package com.aiworkforce.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the AI Workforce Platform Core API.
 *
 * <p>The application is a modular monolith: business domains live in sibling packages
 * ({@code organization}, {@code agent}, {@code project}, ...) and share only the
 * cross-cutting {@code common} and {@code config} packages. See
 * {@code docs/architecture/CORE_BACKEND_ARCHITECTURE.md}.
 */
@SpringBootApplication
public class CoreApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApiApplication.class, args);
    }
}
