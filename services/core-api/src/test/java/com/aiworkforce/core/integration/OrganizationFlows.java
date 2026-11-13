package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Reusable API flows for organization tests. */
abstract class OrganizationFlows extends ApiIntegrationTest {

    private static final Pattern TOKEN_IN_MAIL = Pattern.compile("(?m)^\\s+([A-Za-z0-9_-]{43})\\s*$");

    protected UUID createOrganization(TestUser owner, String name) throws Exception {
        JsonNode created = body(postAs(owner, "/api/v1/organizations", Map.of("name", name)).andExpect(status().isCreated()));
        return UUID.fromString(created.get("id").asString());
    }

    protected UUID roleId(TestUser user, UUID organizationId, String code) throws Exception {
        for (JsonNode role : body(getAs(user, "/api/v1/organizations/{id}/roles", organizationId)
                .andExpect(status().isOk()))) {
            if (role.path("code").isString() && code.equals(role.get("code").asString())) {
                return UUID.fromString(role.get("id").asString());
            }
        }
        throw new AssertionError("No role " + code);
    }

    /** Invites by e-mail and returns the token read from the delivered e-mail. */
    protected String invite(TestUser inviter, UUID organizationId, String email, UUID roleId) throws Exception {
        postAs(inviter, "/api/v1/organizations/{id}/invitations", Map.of("email", email, "roleId", roleId),
                organizationId).andExpect(status().isCreated());
        return tokenSentTo(email);
    }

    protected String tokenSentTo(String email) {
        List<String> texts = mailbox.textsTo(email);
        assertThat(texts).as("invitation e-mail delivered to %s", email).isNotEmpty();
        Matcher matcher = TOKEN_IN_MAIL.matcher(texts.getFirst());
        assertThat(matcher.find()).as("token present in e-mail").isTrue();
        return matcher.group(1);
    }

    /** Invites and accepts: the user becomes a member with the role. Returns the membership id. */
    protected UUID join(TestUser inviter, UUID organizationId, TestUser user, String roleCode) throws Exception {
        String token = invite(inviter, organizationId, user.email(), roleId(inviter, organizationId, roleCode));
        JsonNode accepted = body(postAs(user, "/api/v1/invitations/accept", Map.of("token", token))
                .andExpect(status().isOk()));
        return UUID.fromString(accepted.get("membershipId").asString());
    }

    protected UUID membershipId(TestUser user, UUID organizationId) throws Exception {
        return UUID.fromString(body(getAs(user, "/api/v1/organizations/{id}", organizationId)
                .andExpect(status().isOk())).get("myMembershipId").asString());
    }
}
