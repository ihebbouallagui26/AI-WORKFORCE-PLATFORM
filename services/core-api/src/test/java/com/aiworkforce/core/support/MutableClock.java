package com.aiworkforce.core.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The application clock for recovery tests: the system clock until a test freezes it at an exact instant. */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClock {

    public static final class Switchable extends Clock {

        private final AtomicReference<Instant> frozen = new AtomicReference<>();

        public void freeze(Instant at) {
            frozen.set(at);
        }

        public void release() {
            frozen.set(null);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant at = frozen.get();
            return at != null ? at : Instant.now();
        }
    }

    @Bean
    @Primary
    Switchable switchableClock() {
        return new Switchable();
    }
}
