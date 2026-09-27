package com.chris64233.cc.studyconsent;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

@TestConfiguration
public class TestClockConfig {

    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return MutableClock.utc(T0);
    }
}
