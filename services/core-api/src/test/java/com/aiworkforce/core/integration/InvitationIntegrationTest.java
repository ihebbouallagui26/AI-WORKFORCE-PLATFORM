package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.TestIdentityProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Invitations: delivery by e-mail, hashed single-use tokens, expiry, revocation, e-mail binding. */
class InvitationIntegrationTest extends OrganizationFlows {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anInvitationIsDeliveredByEmailAndTheTokenIsNeverExposedOrStored() throws Exception {
        TestUser owner = newUser("owner");
        TestUser guest = newUser("guest");
        UUID org = createOrganization(owner, "Invites");
        UUID memberRole = roleId(owner, org, "MEMBER");

        String response = postAs(owner, "/api/v1/organizations/{id}/invitations",
                Map.of("email", guest.email().toUpperCase(), "roleId", memberRole), org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(guest.email()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = tokenSentTo(guest.email());
        assertThat(response).doesNotContain(token);
        String listed = getAs(owner, "/api/v1/organizations/{id}/invitations", org).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(listed).doesNotContain(token);

        // Only the SHA-256 of the token is stored.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_invitations WHERE token_hash = ?",
                Long.class, token)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_invitations WHERE token_hash = ?",
                Long.class, sha256(token))).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE details LIKE ?", Long.class,
                "%" + token + "%")).isZero();

        // A second pending invitation for the same address is refused.
        postAs(owner, "/api/v1/organizations/{id}/invitations", Map.of("email", guest.email(), "roleId", memberRole),
                org).andExpect(status().isConflict());
    }

    @Test
    void anInvitationCanBeAcceptedOnlyOnceByTheVerifiedInvitee() throws Exception {
        TestUser owner = newUser("owner");
        TestUser guest = newUser("guest");
        TestUser intruder = newUser("intruder");
        UUID org = createOrganization(owner, "Single use");
        String token = invite(owner, org, guest.email(), roleId(owner, org, "MEMBER"));

        // Holding the token is not enough: the verified e-mail must match.
        postAs(intruder, "/api/v1/invitations/accept", Map.of("token", token)).andExpect(status().isForbidden());
        // Same e-mail but not verified by the identity provider: refused (never authorise by e-mail alone).
        String unverified = TestIdentityProvider.token("sub-unverified-" + UUID.randomUUID())
                .email(guest.email()).emailVerified(false).sign();
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unverified)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("token", token))))
                .andExpect(status().isForbidden());

        postAs(guest, "/api/v1/invitations/accept", Map.of("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(org.toString()))
                .andExpect(jsonPath("$.organizationName").value("Single use"));
        getAs(guest, "/api/v1/organizations/{id}", org).andExpect(status().isOk())
                .andExpect(jsonPath("$.myRoles[0].code").value("MEMBER"));

        // Reuse is refused, also by the same user.
        postAs(guest, "/api/v1/invitations/accept", Map.of("token", token)).andExpect(status().isConflict());
        // Already a member: no new invitation.
        postAs(owner, "/api/v1/organizations/{id}/invitations",
                Map.of("email", guest.email(), "roleId", roleId(owner, org, "ADMIN")), org)
                .andExpect(status().isConflict());
    }

    @Test
    void expiredRevokedUnknownAndMalformedTokensAreRejected() throws Exception {
        TestUser owner = newUser("owner");
        TestUser guest = newUser("guest");
        TestUser other = newUser("other");
        UUID org = createOrganization(owner, "Expiry");
        UUID memberRole = roleId(owner, org, "MEMBER");

        String expired = invite(owner, org, guest.email(), memberRole);
        jdbc.update("UPDATE organization_invitations SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
                sha256(expired));
        postAs(guest, "/api/v1/invitations/accept", Map.of("token", expired)).andExpect(status().isConflict());
        // An expired invitation no longer blocks a new one for the same address.
        mailbox.clear();
        String fresh = invite(owner, org, guest.email(), memberRole);
        assertThat(fresh).isNotEqualTo(expired);

        String revoked = invite(owner, org, other.email(), memberRole);
        UUID invitationId = jdbc.queryForObject("SELECT id FROM organization_invitations WHERE token_hash = ?",
                UUID.class, sha256(revoked));
        deleteAs(owner, "/api/v1/organizations/{o}/invitations/{i}", org, invitationId)
                .andExpect(status().isNoContent());
        deleteAs(owner, "/api/v1/organizations/{o}/invitations/{i}", org, invitationId)
                .andExpect(status().isConflict());
        postAs(other, "/api/v1/invitations/accept", Map.of("token", revoked)).andExpect(status().isConflict());

        postAs(guest, "/api/v1/invitations/accept", Map.of("token", "A".repeat(43))).andExpect(status().isNotFound());
        postAs(guest, "/api/v1/invitations/accept", Map.of("token", "not a token")).andExpect(status().isBadRequest());
        postAs(guest, "/api/v1/invitations/accept", Map.of("token", fresh)).andExpect(status().isOk());
    }

    @Test
    void anInvitationBecomesInvalidWhenItsAuthorLosesTheRightToGrantItsRole() throws Exception {
        TestUser owner = newUser("owner");
        TestUser admin = newUser("admin");
        TestUser guest = newUser("guest");
        UUID org = createOrganization(owner, "Revalidation");
        UUID adminMembership = join(owner, org, admin, "ADMIN");
        String token = invite(admin, org, guest.email(), roleId(owner, org, "ADMIN"));

        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles",
                Map.of("roleIds", java.util.List.of(roleId(owner, org, "MEMBER"))), org, adminMembership)
                .andExpect(status().isOk());
        postAs(guest, "/api/v1/invitations/accept", Map.of("token", token)).andExpect(status().isConflict());
        getAs(guest, "/api/v1/organizations/{id}", org).andExpect(status().isNotFound());
    }

    @Test
    void invitationsOfAnotherOrganizationCannotBeRevoked() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        String token = invite(bob, orgB, "someone." + UUID.randomUUID() + "@example.test", roleId(bob, orgB, "MEMBER"));
        UUID invitationOfB = jdbc.queryForObject("SELECT id FROM organization_invitations WHERE token_hash = ?",
                UUID.class, sha256(token));

        deleteAs(alice, "/api/v1/organizations/{o}/invitations/{i}", orgA, invitationOfB)
                .andExpect(status().isNotFound());
        deleteAs(alice, "/api/v1/organizations/{o}/invitations/{i}", orgB, invitationOfB)
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM organization_invitations WHERE id = ?", String.class,
                invitationOfB)).isEqualTo("PENDING");
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
