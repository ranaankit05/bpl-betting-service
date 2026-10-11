package com.bally.bpl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bpl.pagination")
public record PaginationProperties(int defaultSize, int maxSize) {

    public PaginationProperties {
        if (defaultSize <= 0) {
            defaultSize = 100;
        }
        if (maxSize <= 0) {
            maxSize = 500;
        }
    }
}
