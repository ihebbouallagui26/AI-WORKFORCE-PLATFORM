package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI provider connections. Credentials are write-only: they appear in create and replace requests
 * and in no response, ever.
 */
@RestController
@Validated
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI providers", description = "Organization provider connections (credentials are write-only)")
public class ProviderConnectionController {

    /** Printable ASCII without spaces: API keys and tokens. */
    static final String CREDENTIAL = "^[!-~]+$";

    @Schema(name = "ProviderConnection")
    public record ConnectionResponse(UUID id, ProviderType providerType, String providerName, String displayName,
            String accountIdentifier, ConnectionStatus status, String statusDetail, Instant lastTestedAt,
            Instant createdAt, Instant updatedAt, long version) {

        static ConnectionResponse of(ConnectionView v) {
            return new ConnectionResponse(v.id(), v.providerType(), v.providerType().displayName(), v.displayName(),
                    v.accountIdentifier(), v.status(), v.statusDetail(), v.lastTestedAt(), v.createdAt(),
                    v.updatedAt(), v.version());
        }
    }

    @Schema(name = "ProviderType")
    public record TypeResponse(ProviderType type, String displayName) {
    }

    @Schema(name = "CreateProviderConnectionRequest")
    public record CreateRequest(
            @NotNull ProviderType providerType,
            @NotBlank @Size(min = 2, max = 100) String displayName,
            @Size(max = 200) String accountIdentifier,
            @Schema(description = "Write-only secret. Stored encrypted, never returned.", accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Size(min = 8, max = 512) @Pattern(regexp = CREDENTIAL) String credential) {

        @Override
        public String toString() {
            return "CreateRequest[providerType=" + providerType + ", displayName=" + displayName + ", credential=<redacted>]";
        }
    }

    @Schema(name = "ReplaceProviderCredentialRequest")
    public record CredentialRequest(
            @Schema(description = "Write-only secret. Stored encrypted, never returned.", accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Size(min = 8, max = 512) @Pattern(regexp = CREDENTIAL) String credential) {

        @Override
        public String toString() {
            return "CredentialRequest[credential=<redacted>]";
        }
    }

    @Schema(name = "UpdateProviderConnectionRequest", description = "Omitted fields are left unchanged.")
    public record UpdateRequest(
            @Size(min = 2, max = 100) @Pattern(regexp = ".*\\S.*") String displayName,
            @Size(max = 200) String accountIdentifier,
            Long expectedVersion) {
    }

    @Schema(name = "ProviderConnectionTestResult")
    public record TestResponse(ConnectionResponse connection, ProviderAdapter.Outcome outcome, String message) {
    }

    @Schema(name = "ProviderModel")
    public record ModelResponse(String id, String displayName) {
    }

    private final ProviderConnectionService connections;

    public ProviderConnectionController(ProviderConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping("/api/v1/providers/types")
    @Operation(summary = "List the supported provider types")
    public List<TypeResponse> types() {
        return Arrays.stream(ProviderType.values()).map(t -> new TypeResponse(t, t.displayName())).toList();
    }

    @GetMapping("/api/v1/organizations/{organizationId}/providers/connections")
    @Operation(summary = "List provider connections (provider.read)")
    public PageResponse<ConnectionResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(required = false) ProviderType type,
            @RequestParam(required = false) ConnectionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(connections.list(organizationId, user, type, status,
                PageParams.of(page, size, Sort.by("displayName", "id"))), ConnectionResponse::of);
    }

    @PostMapping(path = "/api/v1/organizations/{organizationId}/providers/connections",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a provider connection; it stays PENDING until tested (provider.connect)")
    public ConnectionResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRequest request) {
        return ConnectionResponse.of(connections.create(organizationId, user, request.providerType(),
                request.displayName(), request.accountIdentifier(), request.credential()));
    }

    @GetMapping("/api/v1/organizations/{organizationId}/providers/connections/{connectionId}")
    @Operation(summary = "Get a provider connection (provider.read)")
    public ConnectionResponse get(@PathVariable UUID organizationId, @PathVariable UUID connectionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return ConnectionResponse.of(connections.get(organizationId, connectionId, user));
    }

    @PatchMapping(path = "/api/v1/organizations/{organizationId}/providers/connections/{connectionId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update display name or account identifier (provider.manage)")
    public ConnectionResponse update(@PathVariable UUID organizationId, @PathVariable UUID connectionId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRequest request) {
        return ConnectionResponse.of(connections.update(organizationId, connectionId, user, request.displayName(),
                request.accountIdentifier(), request.expectedVersion()));
    }

    @PutMapping(path = "/api/v1/organizations/{organizationId}/providers/connections/{connectionId}/credential",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace the credential; the connection becomes PENDING (provider.manage)")
    public ConnectionResponse replaceCredential(@PathVariable UUID organizationId, @PathVariable UUID connectionId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody CredentialRequest request) {
        return ConnectionResponse.of(connections.replaceCredential(organizationId, connectionId, user,
                request.credential()));
    }

    @PostMapping("/api/v1/organizations/{organizationId}/providers/connections/{connectionId}/test")
    @Operation(summary = "Validate the credential with a read-only provider call, no completion (provider.test)")
    public TestResponse test(@PathVariable UUID organizationId, @PathVariable UUID connectionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        ProviderConnectionService.TestResult result = connections.test(organizationId, connectionId, user);
        String message = switch (result.outcome()) {
            case VALID -> "The provider accepted the credential.";
            case INVALID -> "The provider rejected the credential.";
            case UNAVAILABLE -> "The provider could not be reached or did not answer conclusively. Try again.";
        };
        return new TestResponse(ConnectionResponse.of(result.connection()), result.outcome(), message);
    }

    @GetMapping("/api/v1/organizations/{organizationId}/providers/connections/{connectionId}/models")
    @Operation(summary = "Models reported by the provider for this connection (provider.test)")
    public List<ModelResponse> models(@PathVariable UUID organizationId, @PathVariable UUID connectionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return connections.models(organizationId, connectionId, user).stream()
                .map(m -> new ModelResponse(m.id(), m.displayName())).toList();
    }

    @DeleteMapping("/api/v1/organizations/{organizationId}/providers/connections/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the connection and delete its credential for good (provider.manage)")
    public void revoke(@PathVariable UUID organizationId, @PathVariable UUID connectionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        connections.revoke(organizationId, connectionId, user);
    }
}
