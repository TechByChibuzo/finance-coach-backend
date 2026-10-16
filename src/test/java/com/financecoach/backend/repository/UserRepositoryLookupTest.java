package com.financecoach.backend.repository;

import com.financecoach.backend.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Tests the default lookupByEmail method; only the derived query is stubbed.
 */
class UserRepositoryLookupTest {

    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }

    @Test
    void singleMatch_isReturned() {
        User legacy = user("Jane@Example.com");
        when(userRepository.findAllByEmailIgnoreCase("jane@example.com")).thenReturn(List.of(legacy));

        assertThat(userRepository.lookupByEmail("jane@example.com")).contains(legacy);
    }

    @Test
    void duplicatesDifferingInCase_preferTheExactMatch() {
        User exact = user("jane@example.com");
        when(userRepository.findAllByEmailIgnoreCase("jane@example.com"))
                .thenReturn(List.of(user("JANE@example.com"), exact));

        assertThat(userRepository.lookupByEmail("jane@example.com")).contains(exact);
    }

    @Test
    void ambiguousDuplicates_areTreatedAsNotFound_insteadOfThrowing() {
        when(userRepository.findAllByEmailIgnoreCase("jane@example.com"))
                .thenReturn(List.of(user("JANE@example.com"), user("Jane@example.com")));

        assertThat(userRepository.lookupByEmail("jane@example.com")).isEmpty();
    }

    @Test
    void noMatch_isEmpty() {
        when(userRepository.findAllByEmailIgnoreCase("nobody@example.com")).thenReturn(List.of());

        assertThat(userRepository.lookupByEmail("nobody@example.com")).isEmpty();
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }
}
