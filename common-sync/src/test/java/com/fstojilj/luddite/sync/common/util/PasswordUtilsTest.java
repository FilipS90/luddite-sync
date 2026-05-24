package com.fstojilj.luddite.sync.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordUtilsTest {

    // Known SHA-256 hex of "admin123"
    private static final String ADMIN123_HASH = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9";

    @Test
    void hash_knownValue_returnsExpectedHex() {
        assertThat(PasswordUtils.hash("admin123")).isEqualTo(ADMIN123_HASH);
    }

    @Test
    void hash_emptyString_returnsDeterministicHash() {
        String h = PasswordUtils.hash("");
        assertThat(h).isNotBlank().hasSize(64);
    }

    @Test
    void hash_sameInput_returnsSameHash() {
        assertThat(PasswordUtils.hash("secret")).isEqualTo(PasswordUtils.hash("secret"));
    }

    @Test
    void hash_differentInputs_returnDifferentHashes() {
        assertThat(PasswordUtils.hash("password1")).isNotEqualTo(PasswordUtils.hash("password2"));
    }

    @Test
    void hash_result_isLowercaseHex() {
        String h = PasswordUtils.hash("test");
        assertThat(h).matches("[0-9a-f]{64}");
    }
}

