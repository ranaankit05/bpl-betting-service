package com.bally.bpl.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bpl.security")
public record SecurityProperties(List<ApiKeyEntry> apiKeys) {

    public SecurityProperties {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }

    public record ApiKeyEntry(String name, String key, Role role, String userId) {
    }
}
