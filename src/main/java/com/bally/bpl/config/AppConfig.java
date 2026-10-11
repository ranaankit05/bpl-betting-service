package com.bally.bpl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /** Single source of time so business rules (betting cut-off, timestamps) are testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
