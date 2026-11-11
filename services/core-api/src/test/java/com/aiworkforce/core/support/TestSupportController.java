package com.aiworkforce.core.support;

import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints exercising validation, error handling and method security.
 * Lives in test sources and is active only with the {@code test-support} profile:
 * it is never part of the application.
 */
@RestController
@Profile("test-support")
@RequestMapping("/api/v1/test-support")
public class TestSupportController {

    public record EchoRequest(
            @NotBlank @Size(max = 50) String name,
            @Email String email,
            @Min(1) int quantity) {
    }

    @PostMapping("/echo")
    public EchoRequest echo(@Valid @RequestBody EchoRequest request) {
        return request;
    }

    @GetMapping("/search")
    public Map<String, Integer> search(@RequestParam @Min(1) @Max(100) int limit) {
        return Map.of("limit", limit);
    }

    @GetMapping("/items/{id}")
    public Map<String, String> item(@PathVariable String id) {
        throw new ResourceNotFoundException("item");
    }

    @GetMapping("/failure")
    public Map<String, String> failure() {
        throw new IllegalStateException("Connection to jdbc:postgresql://db:5432 failed for password=s3cr3t");
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> adminOnly() {
        return Map.of("result", "ok");
    }
}
