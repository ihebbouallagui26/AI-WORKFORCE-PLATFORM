package com.aiworkforce.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class of API integration tests: full application, real PostgreSQL (separate roles),
 * real SMTP (Mailpit) and signed access tokens validated by the production decoder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestIdentityProvider.class, MailpitConfiguration.class, FakeProviders.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class ApiIntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MailpitConfiguration.Mailbox mailbox;

    /** A distinct test user: unique subject and e-mail per call. */
    protected static TestUser newUser(String name) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new TestUser("sub-" + name + "-" + id, name + "." + id + "@example.test", name);
    }

    public record TestUser(String subject, String email, String name) {

        public String token() {
            return TestIdentityProvider.token(subject).email(email).name(name).sign();
        }
    }

    protected ResultActions call(TestUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()));
    }

    protected ResultActions getAs(TestUser user, String path, Object... vars) throws Exception {
        return call(user, get(path, vars));
    }

    protected ResultActions postAs(TestUser user, String path, Object body, Object... vars) throws Exception {
        return call(user, post(path, vars).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions patchAs(TestUser user, String path, Object body, Object... vars) throws Exception {
        return call(user, patch(path, vars).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions putAs(TestUser user, String path, Object body, Object... vars) throws Exception {
        return call(user, put(path, vars).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions deleteAs(TestUser user, String path, Object... vars) throws Exception {
        return call(user, delete(path, vars));
    }

    protected static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected static String json(Object body) {
        return body instanceof String s ? s : JSON.writeValueAsString(body);
    }
}
