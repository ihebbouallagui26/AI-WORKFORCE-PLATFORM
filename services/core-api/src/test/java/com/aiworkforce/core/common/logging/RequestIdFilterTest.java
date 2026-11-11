package com.aiworkforce.core.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesAWellFormedClientRequestId() {
        assertThat(RequestIdFilter.resolveRequestId("desktop-3f2a9c1e")).isEqualTo("desktop-3f2a9c1e");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "contains spaces here", "line\nbreak-injection", "<script>alert(1)</script>",
            "this-identifier-is-way-too-long-to-be-accepted-as-a-request-id-0123456789"})
    void generatesAUuidForMissingOrUnsafeValues(String candidate) {
        String resolved = RequestIdFilter.resolveRequestId(candidate);
        assertThat(resolved).isNotEqualTo(candidate);
        assertThat(UUID.fromString(resolved)).isNotNull();
    }

    @Test
    void exposesTheIdInTheMdcDuringTheRequestAndCleansUpAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "client-request-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });

        assertThat(seenInChain.get()).isEqualTo("client-request-0001");
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("client-request-0001");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void cleansTheMdcEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // The failure itself is handled elsewhere; only the MDC cleanup matters here.
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isNotBlank();
    }
}
