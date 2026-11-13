package com.aiworkforce.core.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real SMTP server (Mailpit, same image as compose) and a client for its HTTP API. */
@TestConfiguration(proxyBeanMethods = false)
public class MailpitConfiguration {

    public static final DockerImageName MAILPIT_IMAGE = DockerImageName.parse("axllent/mailpit:v1.33.1");

    @Bean
    @SuppressWarnings("resource")
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(MAILPIT_IMAGE)
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/livez").forPort(8025));
    }

    @Bean
    DynamicPropertyRegistrar mailProperties(GenericContainer<?> mailpitContainer) {
        return registry -> {
            mailpitContainer.start();
            registry.add("spring.mail.host", mailpitContainer::getHost);
            registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(1025));
        };
    }

    @Bean
    Mailbox mailbox(GenericContainer<?> mailpitContainer) {
        return new Mailbox(URI.create("http://" + mailpitContainer.getHost() + ":" + mailpitContainer.getMappedPort(8025)));
    }

    /** Reads messages received by Mailpit. */
    public static final class Mailbox {

        private static final JsonMapper JSON = JsonMapper.builder().build();
        private final URI base;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        Mailbox(URI base) {
            this.base = base;
        }

        /** Plain-text bodies of the messages sent to {@code recipient}, newest first. */
        public List<String> textsTo(String recipient) {
            JsonNode list = get("/api/v1/search?query=" + java.net.URLEncoder.encode("to:" + recipient,
                    java.nio.charset.StandardCharsets.UTF_8));
            List<String> texts = new ArrayList<>();
            for (JsonNode message : list.get("messages")) {
                texts.add(get("/api/v1/message/" + message.get("ID").asString()).get("Text").asString());
            }
            return texts;
        }

        public void clear() {
            send(HttpRequest.newBuilder(base.resolve("/api/v1/messages")).DELETE().build());
        }

        private JsonNode get(String path) {
            return JSON.readTree(send(HttpRequest.newBuilder(base.resolve(path)).GET().build()));
        }

        private String send(HttpRequest request) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    throw new IllegalStateException("Mailpit answered " + response.statusCode());
                }
                return response.body();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
