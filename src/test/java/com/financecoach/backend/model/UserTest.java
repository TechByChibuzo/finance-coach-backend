package com.financecoach.backend.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void toString_leavesOutThePasswordHash_soLoggingAUserCantLeakIt() {
        User user = new User("jane@example.com", "$2a$10$secret-hash", "Jane");

        assertThat(user.toString()).contains("jane@example.com").doesNotContain("secret-hash");
    }
}
