package com.bally.bpl;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

@TestConfiguration
public class TestClockConfig {

    public static final Instant BASE = Instant.parse("2026-11-02T10:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(BASE);
    }
}
