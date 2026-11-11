package com.aiworkforce.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsExactOriginsAndTrimsBlankEntries() {
        AppProperties.Cors cors = new AppProperties.Cors(
                List.of(" http://localhost:4200 ", "https://app.example.com", "app://workforce", " "));

        assertThat(cors.allowedOrigins())
                .containsExactly("http://localhost:4200", "https://app.example.com", "app://workforce");
        assertThat(validator.validate(new AppProperties(cors))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*",
            "https://*.example.com",
            "http://localhost:4200/",
            "http://localhost:4200/path",
            "http://localhost:4200?debug=true",
            "https://user:pass@example.com",
            "ftp://example.com",
            "javascript:alert(1)",
            "localhost:4200",
            "not an origin"
    })
    void rejectsWildcardsAndAnythingThatIsNotAnExactOrigin(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() -> new AppProperties.Cors(List.of(origin)));
    }

    @Test
    void requiresAtLeastOneOrigin() {
        AppProperties empty = new AppProperties(new AppProperties.Cors(List.of()));
        AppProperties blank = new AppProperties(new AppProperties.Cors(Arrays.asList(" ", "")));
        AppProperties missing = new AppProperties(new AppProperties.Cors(null));

        assertThat(validator.validate(empty)).isNotEmpty();
        assertThat(validator.validate(blank)).isNotEmpty();
        assertThat(validator.validate(missing)).isNotEmpty();
        assertThat(validator.validate(new AppProperties(null))).isNotEmpty();
    }
}
