package com.aiworkforce.core.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.common.logging.RequestIdFilter;
import com.aiworkforce.core.config.CorsConfig;
import com.aiworkforce.core.config.SecurityConfig;
import com.aiworkforce.core.config.TimeConfig;
import com.aiworkforce.core.support.TestSupportController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Error contract (RFC 9457) for authenticated callers. Authentication is simulated with
 * {@link WithMockUser} in tests only: the application itself has no authentication yet.
 */
@WebMvcTest(controllers = TestSupportController.class)
@Import({SecurityConfig.class, CorsConfig.class, TimeConfig.class})
@ActiveProfiles({"test", "test-support"})
@WithMockUser
class GlobalExceptionHandlerTest {

    private static final String BASE = "/api/v1/test-support";

    @Autowired
    private MockMvc mvc;

    @Test
    void acceptsAValidPayload() throws Exception {
        mvc.perform(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Atlas\",\"email\":\"atlas@example.com\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Atlas"));
    }

    @Test
    void returnsFieldErrorsForAnInvalidPayloadWithoutEchoingValues() throws Exception {
        mvc.perform(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:validation-error"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value(BASE + "/echo"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("name"))
                .andExpect(jsonPath("$.errors[2].field").value("quantity"))
                .andExpect(jsonPath("$.timestamp", endsWith("Z")))
                .andExpect(jsonPath("$.requestId", not(emptyOrNullString())))
                .andExpect(content().string(not(containsString("not-an-email"))));
    }

    @Test
    void returnsParameterErrorsForInvalidQueryParameters() throws Exception {
        mvc.perform(get(BASE + "/search").param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void returnsABadRequestForMalformedJson() throws Exception {
        mvc.perform(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:bad-request"))
                .andExpect(jsonPath("$.requestId", not(emptyOrNullString())));
    }

    @Test
    void returnsUnsupportedMediaTypeAndMethodNotAllowed() throws Exception {
        mvc.perform(post(BASE + "/echo").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:unsupported-media-type"));
        mvc.perform(delete(BASE + "/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:method-not-allowed"));
    }

    @Test
    void returnsNotFoundForMissingResources() throws Exception {
        mvc.perform(get(BASE + "/items/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:not-found"))
                .andExpect(jsonPath("$.detail").value("The requested item was not found."));
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() throws Exception {
        mvc.perform(get(BASE + "/failure").header(RequestIdFilter.REQUEST_ID_HEADER, "support-ticket-0042"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:internal-error"))
                .andExpect(jsonPath("$.requestId").value("support-ticket-0042"))
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "support-ticket-0042"))
                .andExpect(content().string(not(containsString("s3cr3t"))))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("trace"))));
    }

    @Test
    void returnsForbiddenWhenMethodSecurityDeniesAccess() throws Exception {
        mvc.perform(get(BASE + "/admin"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:forbidden"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void allowsMethodSecurityWhenTheRoleMatches() throws Exception {
        mvc.perform(get(BASE + "/admin")).andExpect(status().isOk());
    }
}
