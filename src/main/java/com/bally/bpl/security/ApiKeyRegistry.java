package com.bally.bpl.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of API keys. Keys are hashed with SHA-256 at startup and looked up by hash, so the
 * plaintext is not retained by this component and a lookup never compares secrets character by character.
 * Misconfiguration (no keys, short keys, duplicate keys, a USER without a userId) fails startup.
 */
@Component
public class ApiKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);
    private static final int MIN_KEY_LENGTH = 16;

    private final Map<String, ApiPrincipal> principalsByKeyHash;

    public ApiKeyRegistry(SecurityProperties properties) {
        if (properties.apiKeys().isEmpty()) {
            throw new IllegalStateException("No API keys configured (bpl.security.api-keys)");
        }
        Map<String, ApiPrincipal> map = new HashMap<>();
        for (SecurityProperties.ApiKeyEntry entry : properties.apiKeys()) {
            validate(entry);
            ApiPrincipal principal = new ApiPrincipal(entry.name(), entry.role(),
                    entry.role() == Role.USER ? entry.userId() : null);
            if (map.put(sha256(entry.key()), principal) != null) {
                throw new IllegalStateException("Duplicate API key configured for " + entry.name());
            }
        }
        this.principalsByKeyHash = Map.copyOf(map);
        log.info("Loaded {} API key(s)", principalsByKeyHash.size());
    }

    public Optional<ApiPrincipal> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(principalsByKeyHash.get(sha256(presentedKey)));
    }

    private static void validate(SecurityProperties.ApiKeyEntry entry) {
        if (entry.name() == null || entry.name().isBlank() || entry.role() == null) {
            throw new IllegalStateException("Every API key needs a name and a role");
        }
        if (entry.key() == null || entry.key().length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("API key '" + entry.name() + "' must be at least " + MIN_KEY_LENGTH + " characters");
        }
        if (entry.role() == Role.USER && (entry.userId() == null || entry.userId().isBlank())) {
            throw new IllegalStateException("API key '" + entry.name() + "' has role USER and therefore needs a user-id");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
