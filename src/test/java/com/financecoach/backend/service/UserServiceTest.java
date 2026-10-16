package com.financecoach.backend.service;

import com.financecoach.backend.dto.UserRegistrationRequest;
import com.financecoach.backend.exception.EmailAlreadyExistsException;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private MetricsService metricsService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder);
        ReflectionTestUtils.setField(userService, "metricsService", metricsService);
    }

    // --- email normalization ---

    @Test
    void normalizeEmail_trimsAndLowercases() {
        assertThat(UserService.normalizeEmail("  Jane.Doe@Example.COM ")).isEqualTo("jane.doe@example.com");
        assertThat(UserService.normalizeEmail(null)).isNull();
    }

    @Test
    void registerUser_storesTheNormalizedEmail() {
        when(userRepository.existsByEmailIgnoreCase("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        userService.registerUser(registration("Jane@Example.com"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void registerUser_withSameEmailInDifferentCase_isRejected() {
        when(userRepository.existsByEmailIgnoreCase("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerUser(registration("JANE@example.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void authenticate_matchesEmailRegardlessOfCase() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setPasswordHash("hash");
        when(userRepository.lookupByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);

        User result = userService.authenticate(" Jane@Example.com", "password123");

        assertThat(result).isSameAs(user);
    }

    private UserRegistrationRequest registration(String email) {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail(email);
        request.setPassword("password123");
        request.setFullName("Jane Doe");
        return request;
    }
}
