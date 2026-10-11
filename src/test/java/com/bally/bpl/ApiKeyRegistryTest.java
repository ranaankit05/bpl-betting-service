package com.bally.bpl;

import com.bally.bpl.security.ApiKeyRegistry;
import com.bally.bpl.security.Role;
import com.bally.bpl.security.SecurityProperties;
import com.bally.bpl.security.SecurityProperties.ApiKeyEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyRegistryTest {

    private static final String LONG_KEY = "a-sufficiently-long-secret";

    @Test
    void authenticatesKnownKeysAndBindsUserId() {
        var registry = new ApiKeyRegistry(new SecurityProperties(List.of(
                new ApiKeyEntry("ops", LONG_KEY + "-admin", Role.ADMIN, "ignored"),
                new ApiKeyEntry("app", LONG_KEY + "-user", Role.USER, "U1"))));

        var admin = registry.authenticate(LONG_KEY + "-admin").orElseThrow();
        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.userId()).isNull();                 // admins are never bound to a user

        var user = registry.authenticate(LONG_KEY + "-user").orElseThrow();
        assertThat(user.userId()).isEqualTo("U1");

        assertThat(registry.authenticate("wrong")).isEmpty();
        assertThat(registry.authenticate(null)).isEmpty();
        assertThat(registry.authenticate("  ")).isEmpty();
    }

    @Test
    void failsFastOnUnsafeConfiguration() {
        assertThatThrownBy(() -> new ApiKeyRegistry(new SecurityProperties(List.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeyRegistry(new SecurityProperties(List.of(
                new ApiKeyEntry("weak", "short", Role.ADMIN, null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least");
        assertThatThrownBy(() -> new ApiKeyRegistry(new SecurityProperties(List.of(
                new ApiKeyEntry("nouser", LONG_KEY, Role.USER, null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("user-id");
        assertThatThrownBy(() -> new ApiKeyRegistry(new SecurityProperties(List.of(
                new ApiKeyEntry("a", LONG_KEY, Role.ADMIN, null),
                new ApiKeyEntry("b", LONG_KEY, Role.ADMIN, null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }
}
