package com.aiworkforce.core.invitation;

import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organization invitations. Tokens are never part of any response. */
@RestController
@Validated
@Tag(name = "Invitations", description = "Invitations to join an organization")
public class InvitationController {

    @Schema(name = "Invitation", description = "An invitation. The secret token is only sent by e-mail.")
    public record InvitationResponse(UUID id, String email, UUID roleId, String status, Instant expiresAt,
            UUID invitedBy, Instant createdAt) {

        static InvitationResponse of(InvitationView i) {
            return new InvitationResponse(i.id(), i.email(), i.roleId(), i.status().name(),
                    i.expiresAt(), i.invitedBy(), i.createdAt());
        }
    }

    @Schema(name = "CreateInvitationRequest")
    public record CreateInvitationRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull UUID roleId) {
    }

    @Schema(name = "AcceptInvitationRequest")
    public record AcceptInvitationRequest(@NotBlank @Size(max = 64) String token) {
    }

    @Schema(name = "InvitationAcceptance")
    public record AcceptanceResponse(UUID organizationId, String organizationName, UUID membershipId) {
    }

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping(path = "/api/v1/organizations/{organizationId}/invitations", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List pending invitations (invitation.read)")
    public PageResponse<InvitationResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(invitations.listPending(organizationId, user,
                PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))), InvitationResponse::of);
    }

    @PostMapping(path = "/api/v1/organizations/{organizationId}/invitations",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invite someone by e-mail with a role the caller is allowed to grant (invitation.create)")
    public InvitationResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateInvitationRequest request) {
        return InvitationResponse.of(invitations.create(organizationId, user, request.email(), request.roleId()));
    }

    @DeleteMapping("/api/v1/organizations/{organizationId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a pending invitation (invitation.revoke)")
    public void revoke(@PathVariable UUID organizationId, @PathVariable UUID invitationId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        invitations.revoke(organizationId, invitationId, user);
    }

    @PostMapping(path = "/api/v1/invitations/accept", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Accept an invitation (verified e-mail must match the invitation)")
    public AcceptanceResponse accept(@Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody AcceptInvitationRequest request) {
        InvitationService.Acceptance acceptance = invitations.accept(user, request.token());
        return new AcceptanceResponse(acceptance.organizationId(), acceptance.organizationName(),
                acceptance.membershipId());
    }
}
