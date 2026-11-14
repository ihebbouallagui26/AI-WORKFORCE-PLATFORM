package com.aiworkforce.core.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile of the authenticated user. */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Me", description = "Authenticated user profile")
public class MeController {

    @Schema(name = "UserProfile")
    public record UserProfileResponse(UUID id, String email, boolean emailVerified, String displayName) {
    }

    @Schema(name = "UpdateUserProfileRequest")
    public record UpdateProfileRequest(@NotBlank @Size(max = 200) String displayName) {
    }

    private final UserDirectory directory;

    public MeController(UserDirectory directory) {
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Get the authenticated user's profile")
    public UserProfileResponse me(@Parameter(hidden = true) AuthenticatedUser user) {
        return new UserProfileResponse(user.userId(), user.email(), user.emailVerified(), user.displayName());
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update the authenticated user's display name")
    public UserProfileResponse update(@Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserDirectory.UserSummary updated = directory.rename(user.userId(), request.displayName());
        return new UserProfileResponse(updated.id(), updated.email(), updated.emailVerified(), updated.displayName());
    }
}
